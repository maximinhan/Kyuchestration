package cli

import (
	"bytes"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// reposOwnersJSONDocumentForTest 는 kyu repos owners --json 이 내보내는 문서를 테스트가 직접 다시 적어둔 모양이다.
type reposOwnersJSONDocumentForTest struct {
	SchemaVersion int                           `json:"schemaVersion"`
	Owners        []reposOwnersJSONOwnerForTest `json:"owners"`
}

type reposOwnersJSONOwnerForTest struct {
	Login string `json:"login"`
	Kind  string `json:"kind"`
}

// runReposOwnersForTest 는 repos owners 를 실행하고 stdout 을 문서로 되읽는다. stderr 도 함께 돌려준다.
func runReposOwnersForTest(t *testing.T, gitHub *fakeGitHub, tokenStore secretstore.TokenStore) (reposOwnersJSONDocumentForTest, string) {
	t.Helper()

	var out, errOut bytes.Buffer
	args := []string{"owners", profileOptionName, "개인", machineJSONOptionName}
	if err := BrowseGitHubRepositories(&out, &errOut, args, gitHub.newAccess, tokenStore); err != nil {
		t.Fatalf("BrowseGitHubRepositories() 실패: %v", err)
	}

	var document reposOwnersJSONDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)
	return document, errOut.String()
}

func TestReposOwnersNamesThePersonalAccountFirstAndThenTheOrganizations(t *testing.T) {
	// GUI 가 "어느 계정의 레포를 볼까" 를 그리는 자리다. 대화형 clone 이 번호를 매겨 보여주던
	// 그 목록과 같은 순서여야, 두 화면에서 같은 것을 고른 사용자가 같은 결과를 본다.
	gitHub := newTestGitHub()
	gitHub.organizations = []github.Owner{
		{Login: "acme", IsOrganization: true},
		{Login: "kyu-labs", IsOrganization: true},
	}

	document, _ := runReposOwnersForTest(t, gitHub, storeWithOneProfile(t))

	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", document.SchemaVersion)
	}
	want := []reposOwnersJSONOwnerForTest{
		{Login: "maximinhan", Kind: "user"},
		{Login: "acme", Kind: "org"},
		{Login: "kyu-labs", Kind: "org"},
	}
	if len(document.Owners) != len(want) {
		t.Fatalf("owners = %+v, want %+v", document.Owners, want)
	}
	for ownerIndex, wantOwner := range want {
		if document.Owners[ownerIndex] != wantOwner {
			t.Errorf("owners[%d] = %+v, want %+v", ownerIndex, document.Owners[ownerIndex], wantOwner)
		}
	}
}

func TestReposOwnersKeepsGoingWhenTheTokenCannotSeeOrganizations(t *testing.T) {
	// fine-grained 토큰은 조직 목록에 권한이 없으면 403 으로 답한다. 그것으로 명령을 끝내면
	// 개인 레포를 클론하려던 사용자가 아무것도 하지 못한다 — 대화형 clone 과 같은 규칙이다.
	gitHub := newTestGitHub()
	gitHub.organizationsError = fmt.Errorf("%w (Resource not accessible by personal access token)", github.ErrForbidden)

	document, stderr := runReposOwnersForTest(t, gitHub, storeWithOneProfile(t))

	if len(document.Owners) != 1 || document.Owners[0].Login != "maximinhan" {
		t.Errorf("owners = %+v, 개인 계정만 남기를 기대", document.Owners)
	}
	// 조용히 줄이면 사용자는 자기 조직이 왜 안 보이는지 알 수 없다. 그 말은 stdout 이 아니라
	// stderr 로 나가야 읽는 쪽의 파싱이 깨지지 않는다.
	if !strings.Contains(stderr, "조직 목록") {
		t.Errorf("stderr = %q, 조직 목록을 볼 수 없다는 안내를 기대", stderr)
	}
}

func TestReposOwnersStopsWhenGitHubRejectsTheStoredToken(t *testing.T) {
	// 토큰은 만료되고 폐기된다. 대화형 clone 은 그 자리에서 새 토큰을 받지만, 여기에는
	// 되물을 상대가 없다 — 앱이 자기 화면에서 등록을 이끌 수 있게 실패로 끝낸다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("개인", "만료된-토큰"); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	var out, errOut bytes.Buffer
	err := BrowseGitHubRepositories(&out, &errOut,
		[]string{"owners", profileOptionName, "개인", machineJSONOptionName}, newTestGitHub().newAccess, tokenStore)
	if err == nil {
		t.Fatal("BrowseGitHubRepositories() 가 거절당한 토큰으로 성공했습니다")
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 실패했을 때는 문서를 내지 않기를 기대", out.String())
	}
}

func TestReposOwnersOfAnUnknownProfileListsTheOnesThatExist(t *testing.T) {
	// 이름을 잘못 적었을 때 "없습니다" 만 말하면 앱은 사용자에게 무엇을 고르라고 해야 할지 모른다.
	var out, errOut bytes.Buffer
	err := BrowseGitHubRepositories(&out, &errOut,
		[]string{"owners", profileOptionName, "게인", machineJSONOptionName}, newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("BrowseGitHubRepositories() 가 없는 프로필로 성공했습니다")
	}
	if !strings.Contains(err.Error(), "개인") {
		t.Errorf("에러 = %q, 등록된 프로필 이름을 함께 알리기를 기대", err.Error())
	}
}

func TestReposNeedsAProfileToKnowWhichTokenToUse(t *testing.T) {
	// 머신에 굴러다니는 인증을 집어 쓰지 않는다는 원칙이 여기에도 그대로 있다.
	var out, errOut bytes.Buffer
	err := BrowseGitHubRepositories(&out, &errOut,
		[]string{"owners", machineJSONOptionName}, newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("BrowseGitHubRepositories() 가 프로필 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), profileOptionName) {
		t.Errorf("에러 = %q, 어느 옵션이 필요한지 알리기를 기대", err.Error())
	}
}

func TestReposAnswersOnlyInJSONAndPointsHumansAtClone(t *testing.T) {
	// 이 명령은 GUI·스크립트를 위해서만 있다. 사람이 레포를 고르는 자리는 이미 kyu clone 이고,
	// 같은 일을 하는 화면을 하나 더 만들면 둘이 어긋나기 시작한다.
	var out, errOut bytes.Buffer
	err := BrowseGitHubRepositories(&out, &errOut,
		[]string{"owners", profileOptionName, "개인"}, newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("BrowseGitHubRepositories() 가 --json 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), "kyu clone") {
		t.Errorf("에러 = %q, 사람이 갈 자리를 알리기를 기대", err.Error())
	}
}

func TestReposRefusesAnUnknownSubcommandWithItsUsage(t *testing.T) {
	var out, errOut bytes.Buffer
	err := BrowseGitHubRepositories(&out, &errOut,
		[]string{"onwers", profileOptionName, "개인", machineJSONOptionName}, newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("BrowseGitHubRepositories() 가 알 수 없는 하위 명령으로 성공했습니다")
	}
	if !strings.Contains(err.Error(), "사용법: kyu repos") {
		t.Errorf("에러 = %q, 사용법 안내를 기대", err.Error())
	}
}

// reposListJSONDocumentForTest 는 kyu repos list --json 이 내보내는 문서를 테스트가 직접 다시 적어둔 모양이다.
type reposListJSONDocumentForTest struct {
	SchemaVersion int                        `json:"schemaVersion"`
	Repos         []reposListJSONRepoForTest `json:"repos"`
}

type reposListJSONRepoForTest struct {
	Name      string  `json:"name"`
	FullName  string  `json:"fullName"`
	Private   bool    `json:"private"`
	UpdatedAt *string `json:"updatedAt"`
}

// runReposListForTest 는 repos list 를 실행하고 stdout 을 문서로 되읽는다.
func runReposListForTest(t *testing.T, gitHub *fakeGitHub, ownerLogin string) reposListJSONDocumentForTest {
	t.Helper()

	var out, errOut bytes.Buffer
	args := []string{"list", profileOptionName, "개인", ownerOptionName, ownerLogin, machineJSONOptionName}
	if err := BrowseGitHubRepositories(&out, &errOut, args, gitHub.newAccess, storeWithOneProfile(t)); err != nil {
		t.Fatalf("BrowseGitHubRepositories() 실패: %v", err)
	}

	var document reposListJSONDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)
	return document
}

func TestReposListKeepsTheOrderAndTheFactsGitHubGave(t *testing.T) {
	// 순서는 계약의 일부다. github.com 의 repositories 탭과 같은 최근 갱신 순으로 내려오는데,
	// 앱이 자기 마음대로 다시 정렬하지 않으려면 받은 순서 그대로 넘어가야 한다.
	updatedAt := time.Date(2026, 8, 27, 9, 30, 0, 0, time.UTC)
	gitHub := newTestGitHubWithRepos(
		makeRepository("proj-a", true, updatedAt),
		makeRepository("proj-b", false, updatedAt),
	)

	document := runReposListForTest(t, gitHub, "maximinhan")

	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", document.SchemaVersion)
	}
	if len(document.Repos) != 2 {
		t.Fatalf("repos = %+v, 두 개를 기대", document.Repos)
	}

	first := document.Repos[0]
	if first.Name != "proj-a" || first.FullName != "maximinhan/proj-a" || !first.Private {
		t.Errorf("repos[0] = %+v, 이름·전체 이름·공개 여부를 기대", first)
	}
	if first.UpdatedAt == nil || *first.UpdatedAt != "2026-08-27T09:30:00Z" {
		t.Errorf("repos[0].updatedAt = %v, RFC3339 시각을 기대", first.UpdatedAt)
	}
	if document.Repos[1].Name != "proj-b" || document.Repos[1].Private {
		t.Errorf("repos[1] = %+v, 받은 순서와 공개 여부를 기대", document.Repos[1])
	}
}

func TestReposListAsksTheOrganizationEndpointForAnOwnerThatIsNotTheTokenOwner(t *testing.T) {
	// 레포 목록을 묻는 API 경로가 소유자 종류에 따라 갈린다. 종류를 잘못 잡으면 개인 계정의
	// 레포를 조직 경로로 물어 404 가 나거나, 조직 레포 자리에 내 레포가 나온다.
	gitHub := newTestGitHub()
	gitHub.repositoriesByOwnerLogin = map[string][]github.Repository{
		"acme": {{Name: "platform", OwnerLogin: "acme", CloneURL: "https://github.com/acme/platform.git"}},
	}

	document := runReposListForTest(t, gitHub, "acme")

	if len(gitHub.repositoriesAskedFor) != 1 {
		t.Fatalf("레포를 물은 소유자 = %+v, 한 번을 기대", gitHub.repositoriesAskedFor)
	}
	askedOwner := gitHub.repositoriesAskedFor[0]
	if !askedOwner.IsOrganization {
		t.Errorf("물은 소유자 = %+v, 토큰의 주인이 아닌 이름은 조직으로 묻기를 기대", askedOwner)
	}
	if len(document.Repos) != 1 || document.Repos[0].FullName != "acme/platform" {
		t.Errorf("repos = %+v, 조직의 레포를 기대", document.Repos)
	}
}

func TestReposListAsksThePersonalEndpointForTheTokenOwner(t *testing.T) {
	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))

	runReposListForTest(t, gitHub, "maximinhan")

	if len(gitHub.repositoriesAskedFor) != 1 || gitHub.repositoriesAskedFor[0].IsOrganization {
		t.Errorf("물은 소유자 = %+v, 토큰의 주인은 개인 계정으로 묻기를 기대", gitHub.repositoriesAskedFor)
	}
}

func TestReposListLeavesUpdatedAtNullWhenGitHubDidNotGiveOne(t *testing.T) {
	// 시각이 없는 것과 1970 년은 다른 사실이다. 영 값을 그대로 찍으면 앱은 그 레포를 목록 맨
	// 아래로 밀어내고, 사용자는 방금 만든 레포가 왜 거기 있는지 알 수 없다.
	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Time{}))

	document := runReposListForTest(t, gitHub, "maximinhan")

	if document.Repos[0].UpdatedAt != nil {
		t.Errorf("updatedAt = %v, null 을 기대", *document.Repos[0].UpdatedAt)
	}
}

func TestReposListGivesAnEmptyArrayWhenTheOwnerHasNoRepository(t *testing.T) {
	document := runReposListForTest(t, newTestGitHub(), "maximinhan")

	if document.Repos == nil {
		t.Error("repos = null, 빈 배열을 기대 — 없음을 두 모양으로 다루게 하지 않는다")
	}
	if len(document.Repos) != 0 {
		t.Errorf("repos = %+v, 빈 배열을 기대", document.Repos)
	}
}

func TestReposListNeedsToKnowWhoseRepositoriesToShow(t *testing.T) {
	var out, errOut bytes.Buffer
	err := BrowseGitHubRepositories(&out, &errOut,
		[]string{"list", profileOptionName, "개인", machineJSONOptionName}, newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("BrowseGitHubRepositories() 가 소유자 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), ownerOptionName) {
		t.Errorf("에러 = %q, 어느 옵션이 필요한지 알리기를 기대", err.Error())
	}
}

func TestReposOwnersRefusesTheOwnerOptionItCannotUse(t *testing.T) {
	// 조용히 버리면 사용자는 자기가 지정한 소유자로 걸러진 목록을 보고 있다고 착각한다.
	var out, errOut bytes.Buffer
	err := BrowseGitHubRepositories(&out, &errOut,
		[]string{"owners", profileOptionName, "개인", ownerOptionName, "acme", machineJSONOptionName},
		newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("BrowseGitHubRepositories() 가 owners 에 붙은 --owner 를 받아들였습니다")
	}
	if !strings.Contains(err.Error(), ownerOptionName) {
		t.Errorf("에러 = %q, 어느 옵션이 문제인지 알리기를 기대", err.Error())
	}
}
