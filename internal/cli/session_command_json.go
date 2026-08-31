package cli

import (
	"io"
)

// 이 파일은 kyu session-command 가 내보내는 문서를 정의한다.
//
// list_json.go 와 같은 이유로 직렬화 전용 타입을 따로 둔다 — 이 모양은 도구 밖(데스크톱 앱)과
// 맺는 계약이고, 도구 안의 조립 결과에 무엇이 더 붙느냐가 계약을 바꾸는 일이 되어서는 안 된다.
//
// 다른 문서와 다른 점이 하나 있다. 다른 문서는 앱이 화면에 그릴 사실을 담지만, 이 문서는
// 앱이 그대로 실행할 것을 담는다 — 필드가 틀리면 화면이 어긋나는 것이 아니라 세션이 열리지 않거나
// 엉뚱한 디렉토리에서 열린다.
//
// 스키마 (schemaVersion 1):
//
//	{
//	  "schemaVersion": 1,
//	  "command": ["claude", "--session-id", "d1b9d634-a927-4abf-ad80-5941671b08a8",
//	              "--add-dir", "/home/me/work/WorkDir-featureX/proj-a"],
//	  "cwd": "/home/me/work/WorkDir-featureX",
//	  "env": { "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD": "1" },
//	  "resumedConversationId": null            // 이어가기로 열었으면 그 대화의 ID
//	}
//
// 담지 않는 것이 셋이다(설계 문서 5.2).
//
//	세션 이름 (kyu-<workdir>-<repo>)  앱 세션은 엔진이 아는 세션이 아니다. 앱은 이 이름으로 아무것도 하지 않는다
//	라벨 (main · 레포 이름)            앱이 물을 때 이미 알고 있던 것이다
//	세션 생존 여부                     kyu list --json 이 답하는 사실이고, 여기서 또 답하면 두 문서가 어긋날 자리가 생긴다
//
// 실패도 담지 않는다. 다른 기계용 표면과 같다 — 답할 것이 없었다는 뜻이라 stderr 와 종료 코드 1 이 그 구분을 한다.

// sessionCommandJSONSchemaVersion 은 위 스키마의 판이다.
//
// 판 규약은 다른 문서와 같다. 필드를 더하는 변경으로는 올리지 않고, 필드를 빼거나 이름·의미를
// 바꿀 때만 올린다. resumedConversationId 가 3 단계에서 이 규약으로 더해진 필드다 — 그 필드를
// 모르는 앱은 그것을 무시하고 전과 똑같이 세션을 연다.
const sessionCommandJSONSchemaVersion = 1

// sessionCommandJSONDocument 는 kyu session-command --json 의 출력 한 벌이다.
type sessionCommandJSONDocument struct {
	SchemaVersion int `json:"schemaVersion"`

	// Command 는 앱이 PTY 에서 실행할 argv 다. 순서가 곧 계약이다.
	Command []string `json:"command"`

	// Cwd 는 그 프로세스의 작업 디렉토리다.
	//
	// 레포 세션에서 이 한 줄이 그 레포의 MCP 설정·지침·에이전트를 살린다(설계 문서 1.3).
	// --resume 이 같은 cwd 에서 불려야 하는 이유도 여기 있다 — claude 의 전사가 cwd 별로 갈려 있다(5.5.1).
	Cwd string `json:"cwd"`

	// Env 는 앱이 자기 바탕 환경에 더할 항목이다. 전체 환경이 아니다.
	//
	// 엔진은 앱이 자식에게 물려줄 바탕 환경을 모르고, 엔진의 환경을 그대로 답하면 그것이 앱의
	// 환경을 덮어쓴다. 더할 것이 없으면 null 이 아니라 {} 다 — "없음" 을 두 모양으로 다루게 하지 않는다.
	Env map[string]string `json:"env"`

	// ResumedConversationID 는 이 답이 이어가기로 연 대화의 ID 다. 새 대화면 null 이다.
	//
	// **앱이 resume 실패를 가려내는 근거가 이것 하나다**(설계 문서 5.5.4 의 추천안, 8 절 2 번).
	// 적혀 있는 대화의 전사가 사라졌으면 claude 는 즉시 종료 코드 1 로 끝나는데, 앱이 보는 것은
	// 곧바로 닫힌 PTY 뿐이라 사용자가 /exit 한 것과 구분할 수 없다. 이 필드가 있으면 앱은
	// "이어가기로 연 세션이 0 이 아닌 코드로 끝났다" 를 가려내 그 자리에서 새로 시작할 길을 낸다.
	//
	// 명령에 이미 실려 있는 값을 한 번 더 답하는 것이라 짚어둔다. 앱이 argv 에서 --resume 뒤의
	// 자리를 되읽게 하지 않는 것이 이유다 — 그러면 앱이 조립 규칙을 알아야 하고, 그 앎이
	// 엔진에만 있어야 한다는 것이 원칙 11 이다.
	//
	// 빈 문자열이 아니라 null 이다. 빈 문자열로 두면 "이어간 적 없음" 이 두 모양이 되고,
	// 읽는 쪽이 그 둘을 갈라야 할 이유가 이 문서에는 없다.
	ResumedConversationID *string `json:"resumedConversationId"`
}

// writeSessionCommandAsJSON 은 조립 결과를 앱이 실행할 문서 하나로 내보낸다.
func writeSessionCommandAsJSON(
	out io.Writer,
	command []string,
	cwd string,
	environment []sessionEnvironmentEntry,
	conversation sessionConversation,
) error {
	// 늘 만든다. nil map 은 null 로 직렬화되는데, 그 null 은 앱이 자기 환경에 얹는 자리에서
	// 빈 객체와 다르게 다뤄야 하는 값이 된다.
	environmentToAdd := make(map[string]string, len(environment))
	for _, entry := range environment {
		environmentToAdd[entry.name] = entry.value
	}

	// 새 대화면 필드가 통째로 null 이다. 값이 있는 경우에만 가리킬 것을 만든다.
	var resumedConversationID *string
	if conversation.resumedConversationID != "" {
		resumedConversationID = &conversation.resumedConversationID
	}

	return writeJSONDocument(out, sessionCommandJSONDocument{
		SchemaVersion:         sessionCommandJSONSchemaVersion,
		Command:               command,
		Cwd:                   cwd,
		Env:                   environmentToAdd,
		ResumedConversationID: resumedConversationID,
	})
}
