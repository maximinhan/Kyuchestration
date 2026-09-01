package cli

import (
	"io"
)

// 이 파일은 kyu clone --json 이 내보내는 문서를 정의한다.
//
// list_json.go 와 같은 이유로 직렬화 전용 타입을 따로 둔다 — 이 모양은 도구 밖(데스크톱 GUI·
// 스크립트)과 맺는 계약이고, 도구 안의 타입(cloneAttempt)에 필드를 더하거나 이름을 다듬는 일이
// 계약을 바꾸는 일이 되어서는 안 된다.
//
// 스키마 (cloneJSONSchemaVersion 1) — kyu clone --profile <이름> --repo <owner/name> ... --json:
//
//	{
//	  "schemaVersion": 1,
//	  "results": [                                   // --repo 를 적은 순서 그대로
//	    { "repo": "maximinhan/proj-a", "status": "cloned",  "message": "" },
//	    { "repo": "maximinhan/proj-b", "status": "skipped", "message": "같은 이름의 디렉토리가 이미 있습니다" },
//	    { "repo": "maximinhan/proj-z", "status": "failed",  "message": "maximinhan 의 레포 목록에 proj-z 가 없습니다" }
//	  ],
//	  "mainSessionRestartNeeded": false              // 늘 거짓 — 엔진은 세션을 보지 못한다
//	}
//
// 실패한 레포가 있어도 이 문서는 나온다. 어느 레포가 왜 실패했는지는 여기에만 있으므로,
// 종료 코드만 돌려주면 앱은 사용자에게 아무것도 설명하지 못한다. 그와 별개로 실패가 하나라도
// 있으면 종료 코드는 1 이다 — 스크립트가 보는 것은 그것이다.
//
// 다만 클론을 시작하지도 못한 실패(프로필이 없다, 레포 목록을 받지 못했다)는 이 문서에 담지
// 않는다. 그것은 시도 자체가 없었다는 뜻이라 "전부 실패" 와 구분되어야 하고, 그 구분은
// stderr 와 종료 코드 1 이 이미 하고 있다.

// cloneJSONSchemaVersion 은 kyu clone --json 문서의 판이다.
const cloneJSONSchemaVersion = 1

// cloneJSONDocument 는 클론 한 번의 결과 전부다.
type cloneJSONDocument struct {
	SchemaVersion int               `json:"schemaVersion"`
	Results       []cloneJSONResult `json:"results"`

	// MainSessionRestartNeeded 는 이제 늘 거짓이다.
	//
	// 떠 있는 메인 세션이 방금 클론한 레포를 보지 못하는지를 답하던 자리인데, 엔진에는 그
	// 세션이 떠 있는지 물을 상대가 없어졌다 — 세션은 앱의 PTY 안에 있고 앱만 그것을 안다.
	// 같은 사실을 앱은 자기가 보유한 세션에 대해 스스로 판정할 수 있다.
	//
	// 그래도 필드를 빼지 않는다. 읽는 쪽은 이 이름을 필수로 알고 있어(desktop 의 KyuCloneOutput)
	// 빼는 순간 문서를 통째로 읽지 못하게 되고, 거짓은 그쪽이 이미 받아본 값이다.
	MainSessionRestartNeeded bool `json:"mainSessionRestartNeeded"`
}

type cloneJSONResult struct {
	// Repo 는 사용자가 --repo 에 적은 그대로다. 앱이 자기가 보낸 줄과 이 결과를 맞추는 열쇠다.
	Repo string `json:"repo"`

	// Status 는 cloned · skipped · failed 중 하나다.
	Status string `json:"status"`

	// Message 는 왜 그렇게 끝났는지다. 클론된 레포에서는 빈 문자열이다.
	//
	// null 이 아니라 빈 문자열로 둔다. 여기서 "이유 없음" 과 "빈 이유" 를 가를 것이 없고,
	// 문자열 하나를 두 모양으로 다루게 하면 읽는 쪽이 매번 null 을 확인해야 한다.
	Message string `json:"message"`
}

// writeCloneResultsAsJSON 은 클론 결과를 기계가 읽는 문서 하나로 내보낸다.
func writeCloneResultsAsJSON(out io.Writer, attempts []cloneAttempt) error {
	results := make([]cloneJSONResult, 0, len(attempts))
	for _, attempt := range attempts {
		results = append(results, cloneJSONResult{
			Repo:    attempt.label,
			Status:  string(attempt.status),
			Message: attempt.message,
		})
	}

	return writeJSONDocument(out, cloneJSONDocument{
		SchemaVersion:            cloneJSONSchemaVersion,
		Results:                  results,
		MainSessionRestartNeeded: false,
	})
}
