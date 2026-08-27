package github

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// gitHubAPIBaseURL 은 GitHub REST API 의 주소다.
const gitHubAPIBaseURL = "https://api.github.com"

// apiRequestTimeout 은 API 호출 하나가 기다리는 최대 시간이다.
//
// 무한정 기다리지 않는다. 목록을 묻는 도중 네트워크가 끊기면 사용자는 아무 말 없는 화면 앞에서
// Ctrl-C 를 눌러야 하는지 판단할 수 없다.
const apiRequestTimeout = 30 * time.Second

// repositoriesPerPage 는 한 페이지에 받아올 레포 수다. 100 이 GitHub 이 허용하는 최대치다.
const repositoriesPerPage = "100"

// userAgentHeaderValue 는 GitHub 이 요구하는 클라이언트 이름이다.
//
// 비워 두면 403 으로 끊긴다 — 인증과 무관한 실패라 토큰을 의심하게 만든다.
const userAgentHeaderValue = "kyu"

// apiVersionHeaderValue 는 이 코드가 읽는 응답 형식의 버전이다.
// 못박아 두지 않으면 GitHub 이 기본 버전을 올리는 날 필드가 조용히 달라진다.
const apiVersionHeaderValue = "2022-11-28"

// TokenAccess 는 personal access token 하나로 GitHub 에 붙는 RepositoryAccess 구현이다.
type TokenAccess struct {
	// token 은 사용자가 등록한 personal access token 이다.
	//
	// 이 값은 Authorization 헤더로만 나간다. 에러 메시지·로그·명령 인자 어디에도 싣지 않는다 —
	// 한 번 새 나간 토큰은 사용자가 폐기하기 전까지 계속 유효하다.
	token string

	// apiBaseURL 은 API 주소다. 테스트가 스텁 서버를 가리키게 하려고 필드로 둔다.
	apiBaseURL string

	httpClient *http.Client
}

var _ RepositoryAccess = (*TokenAccess)(nil)

// NewTokenAccess 는 토큰 하나로 GitHub 에 붙는 접근 경로를 만든다.
func NewTokenAccess(token string) *TokenAccess {
	return &TokenAccess{
		token:      token,
		apiBaseURL: gitHubAPIBaseURL,
		httpClient: &http.Client{Timeout: apiRequestTimeout},
	}
}

// AuthenticatedOwner 는 이 토큰의 주인인 개인 계정을 반환한다.
func (access *TokenAccess) AuthenticatedOwner() (Owner, error) {
	var user struct {
		Login string `json:"login"`
	}
	if _, err := access.readJSON(access.apiBaseURL+"/user", &user); err != nil {
		return Owner{}, fmt.Errorf("토큰의 계정 확인 실패: %w", err)
	}
	return Owner{Login: user.Login}, nil
}

// Organizations 는 이 토큰으로 볼 수 있는 소속 조직 전부를 반환한다.
func (access *TokenAccess) Organizations() ([]Owner, error) {
	type organizationPayload struct {
		Login string `json:"login"`
	}

	payloads, err := readAllPages[organizationPayload](access, access.apiBaseURL+"/user/orgs")
	if err != nil {
		return nil, fmt.Errorf("조직 목록 조회 실패: %w", err)
	}

	organizations := make([]Owner, 0, len(payloads))
	for _, payload := range payloads {
		organizations = append(organizations, Owner{Login: payload.Login, IsOrganization: true})
	}
	return organizations, nil
}

// Repositories 는 해당 소유자의 레포를 최근 푸시 순으로 반환한다.
//
// 정렬을 여기서 하지 않고 API 에 맡긴다. 페이지를 끊어 받는 목록이라, 받아온 뒤에 정렬하면
// 첫 페이지 안에서만 맞는 순서가 되고 100 개를 넘는 계정에서 조용히 어긋난다.
func (access *TokenAccess) Repositories(owner Owner) ([]Repository, error) {
	type repositoryPayload struct {
		Name     string    `json:"name"`
		Private  bool      `json:"private"`
		CloneURL string    `json:"clone_url"`
		PushedAt time.Time `json:"pushed_at"`
		Owner    struct {
			Login string `json:"login"`
		} `json:"owner"`
	}

	payloads, err := readAllPages[repositoryPayload](access, access.repositoriesURL(owner))
	if err != nil {
		return nil, fmt.Errorf("%s 의 레포 목록 조회 실패: %w", owner.Login, err)
	}

	repositories := make([]Repository, 0, len(payloads))
	for _, payload := range payloads {
		repositories = append(repositories, Repository{
			Name:         payload.Name,
			OwnerLogin:   payload.Owner.Login,
			CloneURL:     payload.CloneURL,
			IsPrivate:    payload.Private,
			LastPushedAt: payload.PushedAt,
		})
	}
	return repositories, nil
}

// repositoriesURL 은 소유자 종류에 맞는 레포 목록 주소를 만든다.
//
// 개인 계정에 affiliation=owner 를 붙이는 이유: 그것이 없으면 남의 레포에 협업자로 붙어 있는 것과
// 조직 레포까지 섞여 나온다. 워크디렉토리에 클론하려는 것은 내가 고른 소유자의 레포다.
func (access *TokenAccess) repositoriesURL(owner Owner) string {
	if owner.IsOrganization {
		return fmt.Sprintf("%s/orgs/%s/repos?sort=pushed&per_page=%s",
			access.apiBaseURL, url.PathEscape(owner.Login), repositoriesPerPage)
	}
	return fmt.Sprintf("%s/user/repos?affiliation=owner&sort=pushed&per_page=%s",
		access.apiBaseURL, repositoriesPerPage)
}

// readAllPages 는 Link 헤더를 따라 마지막 페이지까지 모은다.
//
// 메서드가 아니라 함수인 이유는 타입 매개변수 때문이다. Go 의 메서드는 자기 타입 매개변수를
// 가질 수 없으므로, 페이지를 모으는 이 하나뿐인 규칙을 조직과 레포가 함께 쓰려면 함수여야 한다.
func readAllPages[T any](access *TokenAccess, firstPageURL string) ([]T, error) {
	var collected []T

	for pageURL := firstPageURL; pageURL != ""; {
		var page []T
		nextPageURL, err := access.readJSON(pageURL, &page)
		if err != nil {
			return nil, err
		}
		collected = append(collected, page...)

		if nextPageURL != "" {
			if err := requireSameHost(firstPageURL, nextPageURL); err != nil {
				return nil, err
			}
		}
		pageURL = nextPageURL
	}

	return collected, nil
}

// requireSameHost 는 다음 페이지 링크가 같은 호스트를 가리키는지 확인한다.
//
// Link 헤더는 응답이 주는 값이라, 그대로 따라가면 응답이 지정한 아무 주소로나 요청을 보내게 된다.
// 그 요청에는 Authorization 헤더가 실려 있다 — 즉 토큰을 남의 호스트에 건네는 길이 열린다.
func requireSameHost(firstPageURL, nextPageURL string) error {
	firstPage, err := url.Parse(firstPageURL)
	if err != nil {
		return fmt.Errorf("API 주소를 읽지 못했습니다: %w", err)
	}
	nextPage, err := url.Parse(nextPageURL)
	if err != nil {
		return fmt.Errorf("다음 페이지 주소를 읽지 못했습니다: %w", err)
	}

	if nextPage.Host != firstPage.Host {
		return fmt.Errorf("다음 페이지가 다른 호스트를 가리킵니다 (%s) — 따라가지 않습니다", nextPage.Host)
	}
	return nil
}

// readJSON 은 요청 하나를 보내 응답을 target 에 담고, 다음 페이지 주소를 돌려준다.
func (access *TokenAccess) readJSON(requestURL string, target any) (nextPageURL string, err error) {
	request, err := http.NewRequest(http.MethodGet, requestURL, nil)
	if err != nil {
		return "", fmt.Errorf("요청 생성 실패: %w", err)
	}

	request.Header.Set("Authorization", "Bearer "+access.token)
	request.Header.Set("Accept", "application/vnd.github+json")
	request.Header.Set("X-GitHub-Api-Version", apiVersionHeaderValue)
	request.Header.Set("User-Agent", userAgentHeaderValue)

	response, err := access.httpClient.Do(request)
	if err != nil {
		// 원래 에러에는 요청 주소가 들어 있다. 토큰은 헤더로만 나가므로 주소에는 섞이지 않는다.
		return "", fmt.Errorf("GitHub API 호출 실패: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		return "", apiResponseError(response)
	}

	if err := json.NewDecoder(response.Body).Decode(target); err != nil {
		return "", fmt.Errorf("GitHub API 응답을 읽지 못했습니다: %w", err)
	}
	return nextPageURLFromLinkHeader(response.Header.Get("Link")), nil
}

// apiResponseError 는 실패 응답을 사용자가 읽을 에러로 옮긴다.
//
// GitHub 이 본문에 실어 보내는 message 를 함께 싣는다. 상태 코드만으로는 무엇이 잘못됐는지
// 알 수 없고, 그 문장이 대개 다음 행동(권한을 켜라, 조직을 승인하라)을 가리킨다.
func apiResponseError(response *http.Response) error {
	var payload struct {
		Message string `json:"message"`
	}
	// 본문을 읽지 못하는 것은 이 함수의 실패가 아니다 — 어차피 아래에서 상태 코드로 답한다.
	body, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
	_ = json.Unmarshal(body, &payload)

	switch response.StatusCode {
	case http.StatusUnauthorized:
		return fmt.Errorf("%w (%s)", ErrInvalidToken, payload.Message)
	case http.StatusForbidden:
		return fmt.Errorf("%w (%s)", ErrForbidden, payload.Message)
	default:
		return fmt.Errorf("GitHub API 가 %s 로 답했습니다 (%s)", response.Status, payload.Message)
	}
}

// nextPageURLFromLinkHeader 는 Link 헤더에서 rel="next" 의 주소를 꺼낸다.
//
// 페이지 번호를 우리가 세지 않는다. GitHub 은 다음 페이지가 없으면 그 링크를 아예 빼는데,
// 번호를 세면 "마지막 페이지가 몇 번인가" 를 따로 알아야 하고 그 계산이 틀리면 한 페이지를 잃거나
// 빈 페이지를 한 번 더 부른다.
func nextPageURLFromLinkHeader(linkHeader string) string {
	for _, link := range strings.Split(linkHeader, ",") {
		segments := strings.Split(strings.TrimSpace(link), ";")
		if len(segments) < 2 {
			continue
		}

		isNextPage := false
		for _, parameter := range segments[1:] {
			if strings.TrimSpace(parameter) == `rel="next"` {
				isNextPage = true
				break
			}
		}
		if !isNextPage {
			continue
		}

		address := strings.TrimSpace(segments[0])
		if strings.HasPrefix(address, "<") && strings.HasSuffix(address, ">") {
			return address[1 : len(address)-1]
		}
	}
	return ""
}
