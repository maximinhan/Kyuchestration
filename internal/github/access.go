// Package github 는 GitHub 에 소유자·레포 목록을 묻고 고른 레포를 클론하는 계층이다.
//
// 이 패키지 밖에서는 GitHub 에 직접 붙지 않는다. 그 규칙이 지켜지는 한 표시 계층(internal/cli)은
// HTTP 도 토큰도 모른 채로 남고, 대화형 흐름을 네트워크 없이 테스트할 수 있다.
//
// 이 패키지는 토큰을 어디서 얻을지 모른다 — 받은 토큰으로 붙기만 한다. 어느 토큰을 쓸지 정하는 것은
// 사용자의 결정이고(internal/secretstore 에 저장된 프로필), 그 결정을 이 계층이 대신하면
// 머신에 굴러다니는 토큰을 집어 쓰는 길이 열린다.
package github

import (
	"errors"
	"time"
)

// ErrInvalidToken 은 GitHub 이 토큰을 거절했을 때(401) 반환한다.
//
// 다른 실패와 갈라 두는 이유는 등록 흐름 때문이다. 토큰이 틀린 것은 사용자가 그 자리에서
// 다시 붙여넣어 고칠 수 있는 유일한 실패라, 그 경우에만 되묻는다.
var ErrInvalidToken = errors.New("GitHub 이 토큰을 거절했습니다")

// ErrForbidden 은 토큰은 유효하지만 그 권한으로 볼 수 없는 것을 물었을 때(403) 반환한다.
//
// fine-grained 토큰은 조직 목록처럼 권한 밖의 것에 403 으로 답한다. 이것을 ErrInvalidToken 과
// 같이 다루면 멀쩡한 토큰을 다시 발급받게 만든다.
var ErrForbidden = errors.New("이 토큰의 권한으로는 볼 수 없습니다")

// Owner 는 레포를 소유한 계정 하나다 — 토큰의 주인인 개인 계정이거나, 그 계정이 속한 조직이다.
type Owner struct {
	// Login 은 GitHub 의 계정 이름이다. 레포 경로(owner/repo)의 앞자리이기도 하다.
	Login string

	// IsOrganization 은 이 소유자가 조직인지다.
	//
	// 레포 목록을 묻는 API 경로가 갈리기 때문에 필요하다 — 개인은 /user/repos, 조직은 /orgs/{org}/repos.
	// 화면에서 개인 계정과 조직을 구분해 보여주는 근거이기도 하다.
	IsOrganization bool
}

// Repository 는 목록에서 고를 수 있는 레포 하나다.
type Repository struct {
	// Name 은 레포 이름이다. 워크디렉토리에 만들어질 디렉토리 이름이 곧 이것이다.
	Name string

	// OwnerLogin 은 이 레포를 소유한 계정 이름이다.
	OwnerLogin string

	// CloneURL 은 API 가 준 HTTPS 클론 주소다.
	//
	// 이름으로 조립하지 않고 API 가 준 것을 그대로 쓴다. 조립하면 GitHub Enterprise 처럼
	// 호스트가 다른 곳에서 엉뚱한 주소를 만들게 된다.
	CloneURL string

	// IsPrivate 는 비공개 레포인지다. 목록에 표시해서, 공개 레포만 있다고 믿고 화면을 공유하는 일을 막는다.
	IsPrivate bool

	// LastUpdatedAt 은 GitHub 이 이 레포를 마지막으로 갱신한 시각이다. 값이 없으면 영 값이다.
	//
	// 목록의 정렬 근거이자 사용자가 "지금 작업하던 그 레포" 를 알아보는 단서다. 푸시 시각이
	// 아니라 갱신 시각인 이유는 순서 때문이다 — 목록은 github.com 의 repositories 탭과 같은
	// 순서로 내려오는데(sort=updated), 화면에 다른 시각을 찍으면 정렬이 어긋나 보인다.
	LastUpdatedAt time.Time
}

// RepositoryAccess 는 토큰 하나로 GitHub 에 붙어 목록을 읽고 레포를 가져오는 한 벌이다.
//
// 인터페이스로 두는 이유는 표시 계층 때문이다. kyu clone 의 대화형 흐름 — 소유자를 고르고,
// 목록에서 번호를 고르고, 이미 있는 것을 건너뛰는 판단 — 은 네트워크와 무관한데,
// 구현 타입을 직접 받으면 그 흐름을 실제 GitHub 없이는 한 줄도 검증할 수 없다.
type RepositoryAccess interface {
	// AuthenticatedOwner 는 이 토큰의 주인인 개인 계정을 반환한다.
	// 토큰이 유효한지 확인하는 자리이기도 하다 — 거절당하면 ErrInvalidToken 이다.
	AuthenticatedOwner() (Owner, error)

	// Organizations 는 이 토큰으로 볼 수 있는 소속 조직 전부를 반환한다.
	Organizations() ([]Owner, error)

	// Repositories 는 해당 소유자의 레포를 최근 갱신 순으로 반환한다.
	Repositories(owner Owner) ([]Repository, error)

	// CloneRepository 는 레포를 destinationPath 에 클론한다.
	//
	// 자격증명을 어떻게 넘길지는 구현의 몫이다 — 부르는 쪽은 토큰을 만지지 않는다.
	// 그래야 표시 계층이 토큰을 화면에 찍거나 로그에 남길 자리 자체가 생기지 않는다.
	CloneRepository(repository Repository, destinationPath string) error
}
