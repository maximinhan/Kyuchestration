package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
)

// 이 파일은 --json 을 붙인 명령이 문서를 내보내는 방법 한 벌이다.
//
// kyu list --json 하나뿐일 때는 그 명령 안에 있어도 됐다. GUI 가 엔진을 부르는 자리가 늘면서
// 같은 규칙을 지켜야 하는 명령이 여럿이 됐고(auth add · auth list · repos · clone), 그 규칙은
// 명령마다 다시 정할 성질의 것이 아니다 — 하나가 들여쓰기를 빼거나 반쪽짜리 문서를 흘리면
// 읽는 쪽은 "이 도구의 JSON" 을 명령별로 다르게 다뤄야 한다.
//
// 문서의 모양(어느 필드에 무엇이 드는가)은 여기 없다. 그것은 명령마다 다른 계약이고,
// 각 명령 옆의 *_json.go 가 직렬화 전용 타입으로 못박는다.

// machineJSONOptionName 은 사람용 출력 대신 기계용 문서를 내라는 옵션이다.
//
// 모든 명령이 같은 낱말을 쓴다. GUI 는 명령마다 다른 이름을 외울 이유가 없고, 사람도 한 번 배운
// --json 이 다음 명령에서도 통해야 이 도구의 기계용 표면을 하나로 기억한다.
const machineJSONOptionName = "--json"

// writeJSONDocument 은 기계가 읽는 문서 하나를 out 에 내보낸다.
//
// 버퍼에 다 만들고 한 번에 내보낸다. out 으로 바로 흘리면 도중에 실패했을 때 반쪽짜리 문서가
// 이미 나가 있고, 읽는 쪽에는 그것이 "깨진 JSON" 으로만 보여 무엇이 실패했는지 알 수 없다.
func writeJSONDocument(out io.Writer, document any) error {
	var rendered bytes.Buffer

	encoder := json.NewEncoder(&rendered)

	// 읽는 쪽은 들여쓰기를 신경 쓰지 않지만, 사람이 이 출력을 눈으로 확인하는 일은 계속 생긴다.
	encoder.SetIndent("", "  ")

	// & 와 < 를 \u0026 · \u003c 로 바꾸지 않는다. 여기 담기는 값은 레포 이름·경로·계정 이름이지
	// HTML 에 박히는 문자열이 아니라, 그 이스케이프는 읽는 사람에게 부담만 된다.
	encoder.SetEscapeHTML(false)

	if err := encoder.Encode(document); err != nil {
		return fmt.Errorf("JSON 문서 조립 실패: %w", err)
	}

	if _, err := out.Write(rendered.Bytes()); err != nil {
		return fmt.Errorf("JSON 문서 출력 실패: %w", err)
	}
	return nil
}
