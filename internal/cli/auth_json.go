package cli

import (
	"io"

	"github.com/maximinhan/Kyuchestration/internal/github"
)

// 이 파일은 kyu auth 의 명령들이 --json 으로 내보내는 문서를 정의한다.
//
// list_json.go 와 같은 이유로 직렬화 전용 타입을 따로 둔다 — 이 모양은 도구 밖(데스크톱 GUI·
// 스크립트)과 맺는 계약이고, 도구 안의 타입(secretstore.Profile, github.Owner)에 필드를 더하거나
// 이름을 다듬는 일이 계약을 바꾸는 일이 되어서는 안 된다.
//
// 스키마 (authAddJSONSchemaVersion 1) — kyu auth add <이름> --json:
//
//	{
//	  "schemaVersion": 1,
//	  "profile": "개인",          // 이 토큰에 붙인 이름. kyu auth remove 에 적는 이름이기도 하다
//	  "login": "maximinhan"      // GitHub 이 확인해준 이 토큰의 주인
//	}
//
// 토큰 값은 이 문서에 없다. 사람용 출력이 토큰을 찍지 않는 이유(화면 공유·캡처)가 기계용에서
// 사라지지 않고, 오히려 이 출력은 로그와 파일에 그대로 남기 쉽다.
//
// 실패도 이 문서에 담지 않는다. 토큰이 거절당했다면 등록이 일어나지 않았다는 뜻이라 성공한
// 등록과 구분되어야 하고, 그 구분은 stderr 와 종료 코드 1 이 이미 하고 있다.

// authAddJSONSchemaVersion 은 kyu auth add --json 문서의 판이다.
//
// 명령마다 따로 센다. 판은 "읽는 쪽이 이 문서를 아는가" 를 가르는 축인데, 도구 전체를 하나로
// 묶어두면 한 명령의 필드를 바꾸느라 올린 판이 그 명령을 읽지도 않는 쪽까지 멈춰 세운다.
const authAddJSONSchemaVersion = 1

// authAddJSONDocument 는 등록이 끝난 프로필 하나다.
type authAddJSONDocument struct {
	SchemaVersion int    `json:"schemaVersion"`
	Profile       string `json:"profile"`

	// Login 은 GitHub 이 확인해준 이 토큰의 주인이다.
	//
	// 이름만 돌려주면 앱은 "저장됐다" 까지만 알고 "무엇이 저장됐는지" 는 모른다. 회사 토큰을
	// 개인 프로필로 등록하는 것은 이 값을 사용자에게 보여줘야만 막힌다.
	Login string `json:"login"`
}

// writeAddedTokenProfileAsJSON 은 등록 결과를 기계가 읽는 문서 하나로 내보낸다.
func writeAddedTokenProfileAsJSON(out io.Writer, profileName string, owner github.Owner) error {
	return writeJSONDocument(out, authAddJSONDocument{
		SchemaVersion: authAddJSONSchemaVersion,
		Profile:       profileName,
		Login:         owner.Login,
	})
}
