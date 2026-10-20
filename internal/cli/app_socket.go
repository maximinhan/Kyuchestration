package cli

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

// 이 파일은 앱 소켓의 자리를 넘기는 옵션 하나다 — kyu session-command · kyu mcp serve · kyu mcp ask
// 가 같은 이름으로 받는다(session-tree-design.md 6.4).
//
// 앱은 세션마다 유닉스 소켓 하나를 열고(chat-ui-design.md 5.4), 그 세션의 claude 자식들은
// 그 소켓으로 앱에게 묻는다. 처음 사용처는 승인 물음이었고 두 번째가 세션 트리 요청이다. 세 명령이
// 같은 이름을 쓰는 이유는 셋이 가리키는 것이 같은 소켓이어서다 — session-command 가 받은 경로를
// 나머지 둘의 등록 인자로 그대로 옮긴다.

// appSocketOptionName 은 앱이 연 소켓의 경로를 넘기는 옵션이다.
//
// **경로를 앱이 정한다.** 소켓을 여는 것은 앱이고 엔진은 그 경로를 argv 의 어느 자리에 넣을지만
// 안다. 엔진이 경로를 스스로 정하면 앱이 그것을 되읽어야 하고, 그러면 답 문서에 필드가 하나
// 늘면서 "엔진이 정하고 앱이 따른다" 와 "앱이 정하고 엔진이 옮긴다" 가 한 표면에 섞인다.
//
// 앞 이름은 --approval-socket 이었다. 이름이 담는 것이 승인 하나가 아니게 되어 옮겼고, 옛 이름을
// 별칭으로 남기지 않는다 — 엔진과 앱은 함께 올라가고, 어긋난 조합은 "알 수 없는 옵션" 으로
// 그 자리에서 드러나는 편이 조용히 반쯤 도는 것보다 낫다.
const appSocketOptionName = "--app-socket"

// appSocketPathAfter 는 args[optionIndex] 의 --app-socket 뒤에 온 경로를 읽는다.
//
// 뒤에 아무것도 없거나 다음 옵션이 온 것을 경로로 읽지 않는다. 읽어버리면 그 세션은 "--json" 이라는
// 소켓에 묻는 세션이 되고, 그 실패는 관문이 처음 열릴 때까지 숨는다.
func appSocketPathAfter(args []string, optionIndex int, usageText string) (string, error) {
	valueIndex := optionIndex + 1
	if valueIndex >= len(args) || strings.HasPrefix(args[valueIndex], "-") {
		return "", fmt.Errorf("%s 뒤에는 앱이 연 소켓의 경로가 와야 합니다\n\n%s", appSocketOptionName, usageText)
	}
	return args[valueIndex], nil
}

// appAnswerReadLimit 은 앱의 답 한 줄에서 읽을 최대 바이트다.
//
// 앱이 고친 인자가 이 줄에 실려 온다 — Write 의 내용이 통째로 들어갈 수 있어 넉넉해야 한다.
// 그래도 한계를 두는 이유는 소켓 너머가 앱이라는 보장이 코드에 없어서다: 끝없이 흘려보내는
// 상대를 만나면 이 프로세스가 메모리를 다 쓸 때까지 읽는다.
const appAnswerReadLimit = 8 << 20

// appConnectTimeout 은 앱 소켓에 붙는 데까지 기다리는 시간이다.
//
// **답을 기다리는 시간에는 시한이 없다.** 실측 3.7 이 150 초 뒤의 답도 통과하는 것을 쟀고,
// 상한을 우리가 정하면 그 숫자보다 오래 고민한 사용자가 이유 없이 거절당한다(설계 5.4.1).
// 여기 있는 시한은 "붙는" 데까지만이다 — 앱이 없으면 곧바로 실패하는 자리라 이 값이 걸리는
// 경우는 소켓 파일은 있는데 듣는 쪽이 멎어 있는 때뿐이다.
const appConnectTimeout = 5 * time.Second

// exchangeLineWithApp 은 소켓 하나를 열어 요청 한 줄을 보내고 답 한 줄을 받는다.
//
// 승인 물음(kyu mcp ask)과 세션 트리 요청(kyu mcp serve)이 함께 쓴다 — 같은 소켓, 같은 줄 단위
// 규약이다(session-tree-design.md 6.4). 답의 모양은 kind 마다 달라서 해석은 부르는 쪽이 한다.
//
// 요청마다 새로 연다. 연결 하나를 계속 쥐고 있으면 두 요청이 겹칠 때 어느 답이 어느 요청의
// 것인지를 이 프로토콜 안에 새로 만들어야 하는데(요청 ID 와 그것을 맞추는 자리), 요청마다
// 연결을 여는 한 그 짝은 연결 자체가 진다.
func exchangeLineWithApp(socketPath string, request any) (json.RawMessage, error) {
	connection, err := net.DialTimeout("unix", socketPath, appConnectTimeout)
	if err != nil {
		return nil, err
	}
	defer connection.Close()

	requestLine, err := json.Marshal(request)
	if err != nil {
		return nil, fmt.Errorf("요청을 조립하지 못했습니다: %w", err)
	}
	if _, err := connection.Write(append(requestLine, '\n')); err != nil {
		return nil, fmt.Errorf("요청을 보내지 못했습니다: %w", err)
	}

	// 시한을 두지 않는다(appConnectTimeout 의 주석). 앱이 죽으면 소켓이 닫히면서 이 읽기가
	// EOF 로 끝나므로, 사람이 사라진 경우와 앱이 사라진 경우는 여기서 갈린다.
	answerLine, err := bufio.NewReader(io.LimitReader(connection, appAnswerReadLimit)).ReadBytes('\n')
	if err != nil && len(answerLine) == 0 {
		return nil, fmt.Errorf("앱이 답하지 않고 연결을 닫았습니다: %w", err)
	}
	return answerLine, nil
}
