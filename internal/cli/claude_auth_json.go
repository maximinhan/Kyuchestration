package cli

import (
	"io"

	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// 이 파일은 kyu claude-auth 가 --json 으로 내보내는 문서를 정의한다.
//
// auth_json.go 와 같은 이유로 직렬화 전용 타입을 따로 둔다 — 이 모양은 도구 밖(데스크톱 앱)과
// 맺는 계약이고, 도구 안의 타입에 필드를 더하는 일이 계약을 바꾸는 일이 되어서는 안 된다.
//
// 스키마 (claudeAuthSetJSONSchemaVersion 1) — kyu claude-auth set --json:
//
//	{
//	  "schemaVersion": 1,
//	  "storage": "keychain"      // 토큰이 실제로 놓인 자리: keychain | secret-service | file
//	}
//
// 스키마 (claudeAuthStatusJSONSchemaVersion 1) — kyu claude-auth status --json:
//
//	{
//	  "schemaVersion": 1,
//	  "stored": true,                        // 저장된 토큰이 있는가
//	  "storage": "keychain",                 // 그 토큰이 놓인 자리. 없으면 빈 문자열
//	  "storageForNewToken": "keychain"       // 지금 저장하면 놓일 자리. 언제나 채워진다
//	}
//
// **토큰 값은 어느 문서에도 없다.** 값을 내는 통로는 `kyu claude-auth token` 하나뿐이고, 그 명령은
// 문서가 아니라 값을 그대로 낸다 — 문서로 감싸면 그 문서가 로그와 파일에 남고, 이 값은 폐기
// 전까지 계속 유효하다.
//
// storageForNewToken 이 stored 와 따로 있는 이유는 화면이 **저장하기 전에** 평문 경고를 내야
// 하기 때문이다. 저장된 뒤에 알리면 사용자가 할 수 있는 일은 이미 저장된 것을 지우는 것뿐이다.

// claudeAuthSetJSONSchemaVersion 은 kyu claude-auth set --json 문서의 판이다.
//
// 명령마다 따로 센다(json_output.go 의 판 규약).
const claudeAuthSetJSONSchemaVersion = 1

// claudeAuthStatusJSONSchemaVersion 은 kyu claude-auth status --json 문서의 판이다.
const claudeAuthStatusJSONSchemaVersion = 1

type claudeAuthSetJSONDocument struct {
	SchemaVersion int    `json:"schemaVersion"`
	Storage       string `json:"storage"`
}

func writeStoredClaudeTokenAsJSON(out io.Writer, storage secretstore.StorageKind) error {
	return writeJSONDocument(out, claudeAuthSetJSONDocument{
		SchemaVersion: claudeAuthSetJSONSchemaVersion,
		Storage:       string(storage),
	})
}

type claudeAuthStatusJSONDocument struct {
	SchemaVersion int  `json:"schemaVersion"`
	Stored        bool `json:"stored"`

	// Storage 는 저장된 토큰이 놓인 자리의 코드다. 저장된 것이 없으면 빈 문자열이다.
	//
	// 빼지 않고 빈 문자열로 둔다. 필드가 있다 없다 하면 읽는 쪽이 "없음" 을 두 모양으로 다뤄야
	// 하는데, 그 구분은 stored 가 이미 하고 있다.
	Storage string `json:"storage"`

	// StorageForNewToken 은 지금 저장하면 토큰이 놓일 자리다. 저장된 것이 있든 없든 채워진다.
	StorageForNewToken string `json:"storageForNewToken"`
}

func writeClaudeTokenStatusAsJSON(
	out io.Writer,
	storedStorage secretstore.StorageKind,
	isStored bool,
	storageForNewToken secretstore.StorageKind,
) error {
	return writeJSONDocument(out, claudeAuthStatusJSONDocument{
		SchemaVersion:      claudeAuthStatusJSONSchemaVersion,
		Stored:             isStored,
		Storage:            string(storedStorage),
		StorageForNewToken: string(storageForNewToken),
	})
}
