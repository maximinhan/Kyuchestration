package cli

import (
	"io"

	"github.com/maximinhan/Kyuchestration/internal/github"
)

// 이 파일은 kyu repos 가 내보내는 문서를 정의한다.
//
// list_json.go 와 같은 이유로 직렬화 전용 타입을 따로 둔다 — 이 모양은 도구 밖(데스크톱 GUI·
// 스크립트)과 맺는 계약이고, 도구 안의 타입(github.Owner)에 필드를 더하거나 이름을 다듬는 일이
// 계약을 바꾸는 일이 되어서는 안 된다.
//
// 스키마 (reposOwnersJSONSchemaVersion 1) — kyu repos owners --profile <이름> --json:
//
//	{
//	  "schemaVersion": 1,
//	  "owners": [                                  // 개인 계정이 늘 첫 자리, 뒤는 소속 조직
//	    { "login": "maximinhan", "kind": "user" },
//	    { "login": "acme", "kind": "org" }
//	  ]
//	}
//
// 이 토큰으로 조직 목록을 볼 수 없으면(fine-grained 403) owners 에는 개인 계정만 남는다.
// 그 사실은 stderr 로 알린다 — 문서에 담으면 "조직이 없다" 와 "조직을 볼 권한이 없다" 를
// 읽는 쪽이 갈라야 하는데, 그 둘에 대해 앱이 할 수 있는 일은 같다(개인 레포를 보여준다).

// reposOwnersJSONSchemaVersion 은 kyu repos owners --json 문서의 판이다.
const reposOwnersJSONSchemaVersion = 1

const (
	// ownerKindUser 는 사람의 계정이다 — 이 토큰의 주인.
	ownerKindUser = "user"

	// ownerKindOrganization 은 조직 계정이다.
	//
	// 코드를 짧게 두는 이유는 이것이 화면의 말이 아니기 때문이다. 사람에게 보여줄 "개인"·"조직"
	// 은 사람용 목록이 따로 만들고, 읽는 쪽은 이 코드로 분기한다.
	ownerKindOrganization = "org"
)

// reposOwnersJSONDocument 는 이 토큰으로 볼 수 있는 소유자 전부다.
type reposOwnersJSONDocument struct {
	SchemaVersion int                    `json:"schemaVersion"`
	Owners        []reposOwnersJSONOwner `json:"owners"`
}

type reposOwnersJSONOwner struct {
	// Login 은 GitHub 의 계정 이름이다. kyu repos list --owner 와 kyu clone --repo 의 앞자리에 그대로 들어간다.
	Login string `json:"login"`

	// Kind 는 이 소유자가 사람인지 조직인지다 — user 또는 org.
	//
	// 참·거짓(isOrganization)이 아니라 낱말로 둔다. 소유자의 종류가 셋이 되는 날(GitHub 은
	// Enterprise 계정을 따로 센다) 참·거짓은 늘릴 수 없고, 읽는 쪽은 그때 통째로 고쳐야 한다.
	Kind string `json:"kind"`
}

// writeOwnersAsJSON 은 소유자 목록을 기계가 읽는 문서 하나로 내보낸다.
func writeOwnersAsJSON(out io.Writer, owners []github.Owner) error {
	listed := make([]reposOwnersJSONOwner, 0, len(owners))
	for _, owner := range owners {
		listed = append(listed, reposOwnersJSONOwner{Login: owner.Login, Kind: ownerKind(owner)})
	}

	return writeJSONDocument(out, reposOwnersJSONDocument{
		SchemaVersion: reposOwnersJSONSchemaVersion,
		Owners:        listed,
	})
}

func ownerKind(owner github.Owner) string {
	if owner.IsOrganization {
		return ownerKindOrganization
	}
	return ownerKindUser
}
