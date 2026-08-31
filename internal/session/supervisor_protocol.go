package session

import "fmt"

// controlProtocolVersion 은 제어 연결과 준비 신호가 함께 쓰는 판 번호다.
//
// 새 kyu 가 옛 감독을 만나는 일이 세션마다 따로 벌어진다 — 세션마다 자기를 띄운 판이 서빙하므로
// 열화가 전면적이지 않고 점진적이다. 감독은 모르는 판의 요청을 badversion 으로 거절한다.
// 그 거절을 받은 클라이언트가 사용자에게 무엇을 시킬지는 설계 문서 10절 4번의 열린 질문이다.
const controlProtocolVersion = 1

// 제어 연산은 둘뿐이다.
//
// create 는 프로토콜이 아니라 프로세스 기동이고(설계 문서 5.3), list 는 디렉토리 스캔이다(5.4).
// 감독에게 물을 것은 자기 생존과 자기 종료뿐이다.
const (
	aliveOperation = "alive"
	killOperation  = "kill"
)

// 실패 코드는 안정된 문자열이고 msg 는 아니다.
//
// 클라이언트는 code 로 센티널 에러를 되살린다 — exists 가 ErrSessionExists 가 되는 식이다.
// msg 는 사람이 읽을 문장이라 언제든 다듬을 수 있어야 하고, 그것으로 분기하면 문구를 고칠 때
// 조용히 깨진다. tmux 백엔드가 stderr 문자열 매칭 대신 has-session 으로 미리 판정한 것과
// 같은 판단이다(tmux.go:49).
const (
	sessionExistsCode      = "exists"
	workingDirMissingCode  = "nocwd"
	commandStartFailedCode = "execfailed"
	badProtocolVersionCode = "badversion"
	unknownOperationCode   = "badop"
	supervisorInternalCode = "internal"
	superviseArgumentsCode = "badargs"
)

// controlRequest 는 제어 연결에 실려 가는 한 줄이다.
//
// 세션 이름이 없다. 소켓이 곧 세션이라 어느 세션인지 말할 필요가 없다(설계 문서 5.5).
type controlRequest struct {
	Version   int    `json:"v"`
	Operation string `json:"op"`
}

// controlResponse 는 감독이 돌려주는 한 줄이다.
type controlResponse struct {
	Version int    `json:"v"`
	OK      bool   `json:"ok"`
	Alive   bool   `json:"alive,omitempty"`
	Code    string `json:"code,omitempty"`
	Message string `json:"msg,omitempty"`
}

// readySignal 은 감독이 기동 직후 부모에게 보내는 한 줄이다.
//
// 제어 응답과 모양이 닮았지만 같은 것이 아니다. 이쪽은 물려받은 파이프에 한 번 쓰고 닫는
// 일회성 신호이고, 저쪽은 소켓 위에서 세션이 사는 내내 오가는 것이다. 하나로 합치면 둘 중
// 한쪽에만 필요한 필드(alive)가 나머지 한쪽에 계속 따라다닌다.
type readySignal struct {
	Version int    `json:"v"`
	OK      bool   `json:"ok"`
	Code    string `json:"code,omitempty"`
	Message string `json:"msg,omitempty"`
}

// controlFailureError 는 감독이 돌려준 실패 코드를 이 패키지의 에러로 되살린다.
//
// 준비 신호와 제어 응답이 같은 코드 어휘를 쓰므로 되살리는 자리도 하나다.
func controlFailureError(sessionName, code, message string) error {
	if code == sessionExistsCode {
		// 호출부가 errors.Is 로 "이미 떠 있으니 새로 만들지 말고 붙어라" 를 판단한다.
		return fmt.Errorf("%w: %s", ErrSessionExists, sessionName)
	}
	if message == "" {
		return fmt.Errorf("감독이 요청을 거절했습니다 (세션 %s, 코드 %s)", sessionName, code)
	}
	return fmt.Errorf("감독이 요청을 거절했습니다 (세션 %s, 코드 %s): %s", sessionName, code, message)
}
