package workdir

// RepoState 는 도구가 관찰해서 얻은 레포의 현재 상태다(설계 문서 5.3).
//
// 세션이 자기 상태를 보고하도록 요구하지 않는다. 관찰 가능한 것 — 세션의 생존과 git 의 답 — 에서만
// 추론한다(설계 원칙 6). 값을 캐시하지 않고 매 조회 시 다시 계산하는 것도 같은 이유다.
// 캐시하는 순간 사용자가 터미널에서 직접 커밋한 변화를 도구가 모르게 되고,
// "관찰해서 얻는다" 가 "기억해둔 것을 말한다" 로 바뀐다.
//
// 값을 int 가 아니라 string 으로 둔 이유는 두 가지다.
//
//  1. 이 값은 `wd list` 에 그대로 찍히는 문자열이다(설계 문서 9.3). int 로 두면 String() 을
//     따로 써야 하고, 그 매핑에서 한 항목을 빠뜨린 채 %v 로 찍으면 사용자에게 숫자가 노출된다.
//  2. 영 값이 유효한 상태와 겹치지 않는다. int 였다면 0 이 네 상태 중 하나가 되어,
//     판정에 실패해 값을 채우지 못한 경우와 정상 판정 결과가 구분되지 않는다.
//     빈 문자열은 어느 상태도 아니므로 그런 실수가 곧바로 드러난다.
type RepoState string

const (
	// RepoStateRunning 은 그 레포의 세션이 살아있는 상태다. 지금 누군가 작업 중이라는 뜻이다.
	RepoStateRunning RepoState = "RUNNING"

	// RepoStateDirty 는 세션이 없는데 커밋되지 않은 변경이 남아있는 상태다.
	RepoStateDirty RepoState = "DIRTY"

	// RepoStateAhead 는 세션이 없고 워킹 트리도 깨끗하지만 업스트림보다 앞선 커밋이 있는 상태다.
	// 푸시가 남았다는 뜻이다.
	RepoStateAhead RepoState = "AHEAD"

	// RepoStateIdle 은 세션도 없고 넘길 것도 남지 않은 상태다.
	RepoStateIdle RepoState = "IDLE"
)
