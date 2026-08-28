package cli

import (
	"io"
	"time"

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
//
// 스키마 (reposListJSONSchemaVersion 1) — kyu repos list --profile <이름> --owner <로그인> --json:
//
//	{
//	  "schemaVersion": 1,
//	  "repos": [                                   // github.com 의 repositories 탭과 같은 최근 갱신 순
//	    {
//	      "name": "proj-a",                        // 워크디렉토리에 만들어질 디렉토리 이름
//	      "fullName": "maximinhan/proj-a",         // kyu clone --repo 에 그대로 넣는 값
//	      "private": true,
//	      "updatedAt": "2026-08-27T09:30:00Z"      // RFC3339(UTC). GitHub 이 주지 않았으면 null
//	    }
//	  ]
//	}

// reposOwnersJSONSchemaVersion 은 kyu repos owners --json 문서의 판이다.
const reposOwnersJSONSchemaVersion = 1

// reposListJSONSchemaVersion 은 kyu repos list --json 문서의 판이다.
const reposListJSONSchemaVersion = 1

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

// reposListJSONDocument 는 한 소유자의 레포 전부다.
type reposListJSONDocument struct {
	SchemaVersion int                 `json:"schemaVersion"`
	Repos         []reposListJSONRepo `json:"repos"`
}

type reposListJSONRepo struct {
	// Name 은 레포 이름이다. 워크디렉토리에 만들어질 디렉토리 이름이 곧 이것이다.
	Name string `json:"name"`

	// FullName 은 owner/name 이다. kyu clone --repo 에 그대로 넣는 값이라, 앱이 두 값을 손으로
	// 이어 붙이지 않아도 되게 여기서 만들어 보낸다.
	FullName string `json:"fullName"`

	Private bool `json:"private"`

	// UpdatedAt 은 GitHub 이 이 레포를 마지막으로 갱신한 시각이다(RFC3339, UTC). 값이 없으면 null 이다.
	//
	// 포인터로 두는 이유는 그 null 이다. 시각이 없는 것과 1970 년은 다른 사실인데, 영 값을 그대로
	// 찍으면 앱은 방금 만든 레포를 목록 맨 아래로 밀어낸다.
	//
	// UTC 로 못박는다. 사람용 목록은 로컬 시간대로 날짜를 찍지만(사용자가 "어제" 를 알아보는 자리),
	// 이 값은 앱이 자기 시간대로 옮겨 보여줄 원본이다 — 실행 머신의 시간대가 섞이면 안 된다.
	UpdatedAt *string `json:"updatedAt"`
}

// writeRepositoriesAsJSON 은 레포 목록을 기계가 읽는 문서 하나로 내보낸다.
//
// 정렬하지 않는다. github.com 의 repositories 탭과 같은 순서로 내려온 것을 그대로 넘긴다 —
// 그 순서는 사용자가 "지금 작업하던 그 레포" 를 알아보는 단서이고, 여기서 다시 정렬하면
// 100 개를 넘는 계정에서 페이지 경계를 따라 조용히 어긋난다.
func writeRepositoriesAsJSON(out io.Writer, repositories []github.Repository) error {
	listed := make([]reposListJSONRepo, 0, len(repositories))
	for _, repository := range repositories {
		listed = append(listed, reposListJSONRepo{
			Name:      repository.Name,
			FullName:  repositoryFullName(repository),
			Private:   repository.IsPrivate,
			UpdatedAt: updatedAtInRFC3339(repository),
		})
	}

	return writeJSONDocument(out, reposListJSONDocument{
		SchemaVersion: reposListJSONSchemaVersion,
		Repos:         listed,
	})
}

// repositoryFullName 은 owner/name 을 만든다. 앞자리는 GitHub 이 답으로 준 소유자다.
//
// 사용자가 --owner 로 적은 이름을 쓰지 않는다. 대소문자가 다르게 적힐 수 있고(GitHub 의 로그인은
// 대소문자를 가리지 않는다), 그 차이가 그대로 kyu clone --repo 로 넘어가면 조회에서 어긋난다.
func repositoryFullName(repository github.Repository) string {
	return repository.OwnerLogin + "/" + repository.Name
}

// updatedAtInRFC3339 는 갱신 시각을 문서에 실을 문자열로 옮긴다. 값이 없으면 nil 이다.
func updatedAtInRFC3339(repository github.Repository) *string {
	if repository.LastUpdatedAt.IsZero() {
		return nil
	}
	formatted := repository.LastUpdatedAt.UTC().Format(time.RFC3339)
	return &formatted
}
