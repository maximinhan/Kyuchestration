package cli

import (
	"fmt"
	"strings"
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
