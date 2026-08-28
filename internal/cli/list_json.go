package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
)

// 이 파일은 kyu list --json 이 내보내는 문서를 정의한다.
//
// 사람용 표와 달리 이 문서는 도구 밖과 맺는 계약이다. 표는 읽는 사람이 알아서 적응하지만,
// 이 문서를 읽는 쪽(데스크톱 GUI·스크립트)은 필드 이름과 모양에 자기 코드를 맞춰두므로
// 여기가 조용히 바뀌면 그쪽이 그대로 깨진다.
//
// 그래서 관찰 결과(workDirListing)를 그대로 직렬화하지 않고 직렬화 전용 타입을 따로 둔다.
// 그 타입들에 필드를 하나 더하거나 이름을 다듬는 일은 이 도구 안의 사정이어야 하고,
// 계약을 바꾸는 일이 되어서는 안 된다. 계약을 바꾸려면 이 파일을 고쳐야 하도록 막아둔 것이다.
//
// 스키마 (schemaVersion 1):
//
//	{
//	  "schemaVersion": 1,
//	  "workDir": {
//	    "name": "WorkDir-featureX",
//	    "absolutePath": "/home/me/work/WorkDir-featureX"
//	  },
//	  "repos": [                                   // 워크디렉토리 바로 아래의 레포, 이름순. 메인 세션은 여기 없다
//	    {
//	      "name": "proj-a",
//	      "absolutePath": "/home/me/work/WorkDir-featureX/proj-a",
//	      "state": "RUNNING",                      // RUNNING | DIRTY | AHEAD | IDLE
//	      "task": {                                // 계획에서 고른 "지금 볼 작업". 고를 것이 없으면 null
//	        "id": "consume",
//	        "status": "blocked",                   // blocked | ready | doing | done
//	        "blockedBy": ["publish"]               // 아직 끝나지 않은 선행. blocked 가 아니면 빈 배열
//	      },
//	      "doneTaskCount": 1,                      // 이 레포를 가리키는 작업 중 끝난 것
//	      "totalTaskCount": 3                      // 이 레포를 가리키는 작업 전체
//	    }
//	  ],
//	  "mainSession": { "alive": false },
//	  "planWarnings": []                           // 사람용 모드에서 stderr 로 나가던 경고
//	}
//
// 실패는 이 문서에 담지 않는다. 워크디렉토리를 읽지 못했다면 관찰 자체가 없었다는 뜻이라
// 빈 목록과 구분되어야 하고, 그 구분은 stderr 와 종료 코드 1 이 이미 하고 있다.

// listJSONSchemaVersion 은 위 스키마의 판이다.
//
// 읽는 쪽이 "이 판을 내가 아는가" 를 판단할 축이다. 필드를 더하는 변경으로는 올리지 않는다 —
// 모르는 필드는 무시하면 그만이라 이미 돌고 있는 독자가 깨지지 않는다. 필드를 빼거나 이름·의미를
// 바꿀 때만 올린다. 그때 읽는 쪽은 아는 판이 아님을 알아보고 멈출 수 있어야 한다.
const listJSONSchemaVersion = 1

// listJSONDocument 는 kyu list --json 의 출력 한 벌이다.
type listJSONDocument struct {
	SchemaVersion int                 `json:"schemaVersion"`
	WorkDir       listJSONWorkDir     `json:"workDir"`
	Repos         []listJSONRepo      `json:"repos"`
	MainSession   listJSONMainSession `json:"mainSession"`
	PlanWarnings  []string            `json:"planWarnings"`
}

type listJSONWorkDir struct {
	Name         string `json:"name"`
	AbsolutePath string `json:"absolutePath"`
}

type listJSONRepo struct {
	Name         string `json:"name"`
	AbsolutePath string `json:"absolutePath"`
	State        string `json:"state"`

	// Task 는 계획에서 고른 "지금 볼 작업" 이다. 고를 것이 없으면 null 이라, 포인터로 둔다.
	// 빈 객체로 채우면 읽는 쪽이 id 가 빈 작업과 작업 없음을 갈라야 한다.
	Task *listJSONTask `json:"task"`

	DoneTaskCount  int `json:"doneTaskCount"`
	TotalTaskCount int `json:"totalTaskCount"`
}

type listJSONTask struct {
	ID     string `json:"id"`
	Status string `json:"status"`

	// BlockedBy 는 이 작업이 기다리는, 아직 끝나지 않은 선행의 id 다. 막힌 작업이 아니면 빈 배열이다.
	BlockedBy []string `json:"blockedBy"`
}

// listJSONMainSession 은 메인 세션의 상태다.
//
// 참·거짓 하나를 굳이 객체로 감싼다. 메인 세션은 레포와 다른 종류의 자리라 repos 에 섞을 수 없고
// (설계 문서 5.4), 최상위에 alive 라는 낱말만 놓으면 그것이 무엇의 생존인지가 이름에서 사라진다.
type listJSONMainSession struct {
	Alive bool `json:"alive"`
}

// writeWorkDirListingAsJSON 은 관찰 결과를 기계가 읽는 문서 하나로 내보낸다.
func writeWorkDirListingAsJSON(out io.Writer, listing workDirListing) error {
	// 버퍼에 다 만들고 한 번에 내보낸다. out 으로 바로 흘리면 도중에 실패했을 때 반쪽짜리 문서가
	// 이미 나가 있고, 읽는 쪽에는 그것이 "깨진 JSON" 으로만 보여 무엇이 실패했는지 알 수 없다.
	var rendered bytes.Buffer

	encoder := json.NewEncoder(&rendered)

	// 읽는 쪽은 들여쓰기를 신경 쓰지 않지만, 사람이 이 출력을 눈으로 확인하는 일은 계속 생긴다.
	encoder.SetIndent("", "  ")

	// & 와 < 를 \u0026 · \u003c 로 바꾸지 않는다. 여기 담기는 값은 레포 이름·경로·작업 id 이지
	// HTML 에 박히는 문자열이 아니라, 그 이스케이프는 읽는 사람에게 부담만 된다.
	encoder.SetEscapeHTML(false)

	if err := encoder.Encode(newListJSONDocument(listing)); err != nil {
		return fmt.Errorf("목록 JSON 조립 실패: %w", err)
	}

	if _, err := out.Write(rendered.Bytes()); err != nil {
		return fmt.Errorf("목록 JSON 출력 실패: %w", err)
	}
	return nil
}

// newListJSONDocument 는 관찰 결과를 계약이 정한 모양으로 옮긴다.
func newListJSONDocument(listing workDirListing) listJSONDocument {
	repos := make([]listJSONRepo, 0, len(listing.repos))
	for _, repo := range listing.repos {
		repos = append(repos, listJSONRepo{
			Name:           repo.name,
			AbsolutePath:   repo.absolutePath,
			State:          string(repo.state),
			Task:           newListJSONTask(repo.planSummary),
			DoneTaskCount:  repo.planSummary.doneTaskCount,
			TotalTaskCount: repo.planSummary.totalTaskCount,
		})
	}

	return listJSONDocument{
		SchemaVersion: listJSONSchemaVersion,
		WorkDir: listJSONWorkDir{
			Name:         listing.workDirName,
			AbsolutePath: listing.workDirAbsolutePath,
		},
		Repos:        repos,
		MainSession:  listJSONMainSession{Alive: listing.mainSessionAlive},
		PlanWarnings: alwaysAnArray(listing.planWarnings),
	}
}

// newListJSONTask 는 계획 요약에서 고른 작업을 문서의 한 항목으로 옮긴다. 고른 것이 없으면 nil 이다.
func newListJSONTask(summary repoPlanSummary) *listJSONTask {
	if !summary.hasTaskToShow {
		return nil
	}
	return &listJSONTask{
		ID:        summary.taskToShow.ID,
		Status:    string(summary.taskToShow.Status),
		BlockedBy: alwaysAnArray(summary.unfinishedNeeds),
	}
}

// alwaysAnArray 는 비어 있는 목록이 null 이 아니라 [] 로 나가게 한다.
//
// Go 는 nil 슬라이스를 null 로 직렬화한다. 그대로 두면 읽는 쪽이 "없음" 을 null 과 [] 두 모양으로
// 다뤄야 하는데, 그 둘을 가르는 의미가 이 문서에는 없다 — 경고가 없는 것과 빈 경고 목록은 같은 사실이다.
func alwaysAnArray(values []string) []string {
	if values == nil {
		return []string{}
	}
	return values
}
