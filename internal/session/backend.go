// Package session 은 세션의 생성·진입·종료를 담당하는 계층이다.
//
// 설계 문서 5.1 의 3층 구조에서 유일한 플랫폼 의존부이며,
// 이 패키지 밖에서는 tmux 를 직접 호출하지 않는다.
// 그 규칙이 지켜지는 한 다른 플랫폼 지원은 이 패키지에 파일 하나를 더하는 것으로 끝난다.
package session

import "errors"

// ErrSessionExists 는 만들려는 이름의 세션이 이미 있을 때 Create 가 반환한다.
//
// 호출부가 "이미 떠 있으니 새로 만들지 말고 붙어라" 를 판단해야 하므로
// 문자열 비교가 아닌 errors.Is 로 식별할 수 있는 센티널 에러로 둔다.
var ErrSessionExists = errors.New("세션이 이미 존재합니다")

// SessionBackend 는 세션 백엔드가 제공해야 하는 최소 기능이다.
// 플랫폼 의존부는 이 5개 함수가 전부다.
type SessionBackend interface {
	// Create 는 cwd 에서 cmd 를 실행하는 백그라운드 세션을 만든다.
	// 이미 같은 이름의 세션이 있으면 ErrSessionExists 를 반환한다.
	Create(name, cwd string, cmd []string) error

	// Attach 는 호출한 터미널을 해당 세션에 연결한다.
	// 사용자가 빠져나올 때까지 블로킹된다.
	Attach(name string) error

	// Kill 은 세션을 종료한다. 없으면 nil 을 반환한다(멱등).
	Kill(name string) error

	// List 는 이 백엔드가 관리하는 세션 이름 전체를 반환한다.
	List() ([]string, error)

	// IsAlive 는 세션의 생존 여부를 반환한다.
	IsAlive(name string) (bool, error)
}
