package github

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// testToken 은 스텁 서버가 받기를 기대하는 토큰이다.
//
// 실제 토큰처럼 생긴 문자열을 쓰지 않는다. 테스트 출력이나 로그에 남았을 때 사람이 그것을
// 진짜 토큰으로 오해하고 폐기 절차를 밟게 만들 이유가 없다.
const testToken = "테스트-토큰"

// newStubbedTokenAccess 는 스텁 서버를 바라보는 TokenAccess 를 만든다.
//
// 실제 api.github.com 에 붙지 않는다. 네트워크가 없는 곳에서도 돌아야 하고, 무엇보다 목록을
// 읽는 코드가 검증해야 하는 것은 "GitHub 이 무엇을 돌려주는가" 가 아니라 "그 답을 어떻게 읽는가" 다.
func newStubbedTokenAccess(t *testing.T, handler http.HandlerFunc) *TokenAccess {
	t.Helper()

	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)

	return &TokenAccess{token: testToken, apiBaseURL: server.URL, httpClient: server.Client()}
}

func TestAuthenticatedOwnerReadsTheAccountTheTokenBelongsTo(t *testing.T) {
	// 토큰을 등록할 때 "이 토큰이 누구인가" 를 사용자에게 보여주는 근거가 이 호출이다.
	// 계정을 잘못 읽으면 사용자는 회사 토큰을 개인 토큰으로 알고 저장하게 된다.
	access := newStubbedTokenAccess(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/user" {
			t.Errorf("요청 경로 = %q, want /user", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer "+testToken {
			t.Errorf("Authorization = %q, 토큰을 Bearer 로 싣기를 기대", got)
		}
		// User-Agent 가 없으면 GitHub 은 403 으로 끊는다. 스텁에서 미리 못박지 않으면
		// 실제 API 앞에서만 드러난다.
		if r.Header.Get("User-Agent") == "" {
			t.Error("User-Agent 가 비어 있습니다 — GitHub 은 User-Agent 없는 요청을 거절한다")
		}
		fmt.Fprint(w, `{"login": "maximinhan"}`)
	})

	owner, err := access.AuthenticatedOwner()
	if err != nil {
		t.Fatalf("AuthenticatedOwner() 실패: %v", err)
	}

	if owner.Login != "maximinhan" {
		t.Errorf("Login = %q, want maximinhan", owner.Login)
	}
	if owner.IsOrganization {
		t.Error("IsOrganization = true, 토큰의 주인은 개인 계정이기를 기대")
	}
}

func TestOrganizationsCollectsEveryPageTheLinkHeaderPointsTo(t *testing.T) {
	// 조직이 30 개를 넘는 계정에서 첫 페이지만 읽으면, 사용자는 자기가 속한 조직이 목록에 없는
	// 이유를 알 수 없다. 페이지를 끝까지 따라가는지는 여기서만 드러난다.
	access := newStubbedTokenAccess(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Query().Get("page") {
		case "":
			w.Header().Set("Link", `<`+"http://"+r.Host+`/user/orgs?page=2>; rel="next", <http://`+r.Host+`/user/orgs?page=2>; rel="last"`)
			fmt.Fprint(w, `[{"login": "first-org"}]`)
		case "2":
			fmt.Fprint(w, `[{"login": "second-org"}]`)
		default:
			t.Errorf("예상하지 못한 페이지 요청: %s", r.URL.String())
		}
	})

	organizations, err := access.Organizations()
	if err != nil {
		t.Fatalf("Organizations() 실패: %v", err)
	}

	if len(organizations) != 2 {
		t.Fatalf("조직 = %+v, 두 페이지를 모두 모으기를 기대", organizations)
	}
	if organizations[0].Login != "first-org" || organizations[1].Login != "second-org" {
		t.Errorf("조직 = %+v, 페이지 순서대로 모으기를 기대", organizations)
	}
	for _, organization := range organizations {
		if !organization.IsOrganization {
			t.Errorf("%s 의 IsOrganization = false, 조직으로 표시하기를 기대", organization.Login)
		}
	}
}

func TestRepositoriesOfThePersonalAccountAsksForOwnedReposSortedByPush(t *testing.T) {
	// 정렬과 소속을 서버에 맡긴다. 최근 푸시 순이 아니면 사용자는 지금 작업할 레포를 찾으러
	// 목록을 훑어야 하고, affiliation 을 빼면 남의 레포에 협업자로 붙어 있는 것까지 섞여 나온다.
	access := newStubbedTokenAccess(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/user/repos" {
			t.Errorf("요청 경로 = %q, want /user/repos", r.URL.Path)
		}
		query := r.URL.Query()
		if query.Get("affiliation") != "owner" {
			t.Errorf("affiliation = %q, want owner", query.Get("affiliation"))
		}
		if query.Get("sort") != "pushed" {
			t.Errorf("sort = %q, want pushed", query.Get("sort"))
		}
		if query.Get("per_page") != "100" {
			t.Errorf("per_page = %q, want 100", query.Get("per_page"))
		}
		fmt.Fprint(w, `[
			{"name": "Kyuchestration", "private": true, "clone_url": "https://github.com/maximinhan/Kyuchestration.git",
			 "pushed_at": "2026-08-27T11:22:33Z", "owner": {"login": "maximinhan"}},
			{"name": "빈-레포", "private": false, "clone_url": "https://github.com/maximinhan/empty.git",
			 "pushed_at": null, "owner": {"login": "maximinhan"}}
		]`)
	})

	repositories, err := access.Repositories(Owner{Login: "maximinhan"})
	if err != nil {
		t.Fatalf("Repositories() 실패: %v", err)
	}

	if len(repositories) != 2 {
		t.Fatalf("레포 = %+v, 두 개를 기대", repositories)
	}

	first := repositories[0]
	if first.Name != "Kyuchestration" || first.OwnerLogin != "maximinhan" {
		t.Errorf("첫 레포 = %+v, 이름과 소유자를 읽기를 기대", first)
	}
	if !first.IsPrivate {
		t.Error("private 레포를 공개로 읽었습니다 — 목록에서 그 표시가 사라진다")
	}
	if first.CloneURL != "https://github.com/maximinhan/Kyuchestration.git" {
		t.Errorf("CloneURL = %q, API 가 준 주소를 그대로 쓰기를 기대", first.CloneURL)
	}
	if !first.LastPushedAt.Equal(time.Date(2026, 8, 27, 11, 22, 33, 0, time.UTC)) {
		t.Errorf("LastPushedAt = %v, want 2026-08-27T11:22:33Z", first.LastPushedAt)
	}

	// 한 번도 푸시하지 않은 레포의 pushed_at 은 null 이다. 그것을 읽지 못해 실패하면
	// 레포 하나 때문에 목록 전체가 뜨지 않는다.
	if !repositories[1].LastPushedAt.IsZero() {
		t.Errorf("푸시한 적 없는 레포의 LastPushedAt = %v, 영 값을 기대", repositories[1].LastPushedAt)
	}
}

func TestRepositoriesOfAnOrganizationAsksTheOrganizationPath(t *testing.T) {
	// 조직 레포를 /user/repos 로 물으면 소속만으로는 걸러지지 않는다 — 조직 경로가 따로 있는 이유다.
	access := newStubbedTokenAccess(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/orgs/some-org/repos" {
			t.Errorf("요청 경로 = %q, want /orgs/some-org/repos", r.URL.Path)
		}
		if r.URL.Query().Get("affiliation") != "" {
			t.Errorf("affiliation = %q, 조직 경로에는 붙이지 않기를 기대", r.URL.Query().Get("affiliation"))
		}
		fmt.Fprint(w, `[{"name": "org-repo", "private": false, "clone_url": "https://github.com/some-org/org-repo.git",
			 "pushed_at": "2026-01-02T03:04:05Z", "owner": {"login": "some-org"}}]`)
	})

	repositories, err := access.Repositories(Owner{Login: "some-org", IsOrganization: true})
	if err != nil {
		t.Fatalf("Repositories() 실패: %v", err)
	}

	if len(repositories) != 1 || repositories[0].Name != "org-repo" {
		t.Errorf("레포 = %+v, 조직 레포 하나를 기대", repositories)
	}
}

func TestUnauthorizedResponseIsReportedAsAnInvalidTokenWithoutLeakingTheToken(t *testing.T) {
	// 등록 흐름이 "다시 붙여넣으세요" 로 이어지려면 401 을 다른 실패와 구분할 수 있어야 한다.
	// 그리고 그 안내에 토큰이 섞이면, 사용자가 화면을 캡처해 붙이는 순간 토큰이 새 나간다.
	access := newStubbedTokenAccess(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		fmt.Fprint(w, `{"message": "Bad credentials"}`)
	})

	_, err := access.AuthenticatedOwner()
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("에러 = %v, ErrInvalidToken 을 기대", err)
	}
	if strings.Contains(err.Error(), testToken) {
		t.Errorf("에러 = %q, 토큰 값을 담지 않기를 기대", err.Error())
	}
}

func TestForbiddenResponseIsReportedSeparatelyFromAnInvalidToken(t *testing.T) {
	// fine-grained 토큰은 조직 목록에 권한이 없으면 403 으로 답한다. 그것을 "토큰이 틀렸다" 로
	// 읽으면 멀쩡한 토큰을 다시 발급받게 만든다 — 부르는 쪽이 두 경우를 갈라 다룰 수 있어야 한다.
	access := newStubbedTokenAccess(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		fmt.Fprint(w, `{"message": "Resource not accessible by personal access token"}`)
	})

	_, err := access.Organizations()
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("에러 = %v, ErrForbidden 을 기대", err)
	}
	if errors.Is(err, ErrInvalidToken) {
		t.Error("403 을 ErrInvalidToken 으로도 읽었습니다 — 두 경우가 갈라지지 않는다")
	}
	if strings.Contains(err.Error(), testToken) {
		t.Errorf("에러 = %q, 토큰 값을 담지 않기를 기대", err.Error())
	}
}
