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
//	  "env": { "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD": "1" }
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
// 바꿀 때만 올린다. 이 규약이 있어서 3 단계가 resume 실패를 가려낼 필드를 나중에 더해도
// (설계 문서 5.5.4) 이미 도는 앱이 깨지지 않는다 — 그래서 그 필드를 지금 미리 넣지 않는다.
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
}

// writeSessionCommandAsJSON 은 조립 결과를 앱이 실행할 문서 하나로 내보낸다.
func writeSessionCommandAsJSON(out io.Writer, command []string, cwd string, environment []sessionEnvironmentEntry) error {
	// 늘 만든다. nil map 은 null 로 직렬화되는데, 그 null 은 앱이 자기 환경에 얹는 자리에서
	// 빈 객체와 다르게 다뤄야 하는 값이 된다.
	environmentToAdd := make(map[string]string, len(environment))
	for _, entry := range environment {
		environmentToAdd[entry.name] = entry.value
	}

	return writeJSONDocument(out, sessionCommandJSONDocument{
		SchemaVersion: sessionCommandJSONSchemaVersion,
		Command:       command,
		Cwd:           cwd,
		Env:           environmentToAdd,
	})
}
