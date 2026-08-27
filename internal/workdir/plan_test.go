package workdir

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
)

// designDocumentPlanFile 은 설계 문서 6.1 의 plan.md 예시를 그대로 옮긴 것이다.
//
// 문서에 적힌 예시가 실제로 파싱되는 것이 이 파서의 첫 번째 요구사항이라 손대지 않고 둔다.
// 본문에 YAML 로는 해석되지 않는 줄이 섞여 있는 것도 그대로다 —
// "SomeEvent(id: Long, ownerId: Long, occurredAt: Instant)" 는 콜론이 여러 번 나와
// YAML 에 넘기면 파싱이 실패한다. 본문을 잘라내지 않으면 이 테스트가 통과할 수 없다.
const designDocumentPlanFile = `---
tasks:
  - id: commons-event
    repo: proj-a
    title: 이벤트 클래스 추가
    needs: []
    status: done

  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    needs: [commons-event]
    status: ready

  - id: consume
    repo: proj-c
    title: 컨슈머 구현
    needs: [commons-event]
    status: blocked
---

## 확정 인터페이스

다른 레포가 의존하는 시그니처를 그대로 적는다. 요약하지 않는다.

    SomeEvent(id: Long, ownerId: Long, occurredAt: Instant)
    채널: <채널 식별자>

## 배경

이 작업을 하는 이유와 결정 근거.

## 작업 순서 근거

commons-event 가 머지되어야 나머지 두 레포가 컴파일된다.
publish 와 consume 은 서로 의존하지 않으므로 병렬 가능.
`

// newWorkDirWithPlanFile 은 계획 파일 하나만 든 임시 워크디렉토리를 만들고 그 경로를 돌려준다.
//
// 경로를 LoadPlan 이 쓰는 상수로 조립하지 않고 ".coord/plan.md" 를 문자열로 적는다.
// 상수를 쓰면 이름이 바뀔 때 테스트도 함께 따라가 규약이 바뀐 사실이 드러나지 않는다.
// 이 경로는 설계 문서 6절이 정한 규약이므로 테스트가 따로 못 박고 있어야 한다.
func newWorkDirWithPlanFile(t *testing.T, planFileContent string) string {
	t.Helper()

	workDirPath := t.TempDir()
	coordDirectoryPath := filepath.Join(workDirPath, ".coord")
	if err := os.MkdirAll(coordDirectoryPath, 0o755); err != nil {
		t.Fatalf("테스트용 .coord 디렉토리 생성 실패: %v", err)
	}
	if err := os.WriteFile(filepath.Join(coordDirectoryPath, "plan.md"), []byte(planFileContent), 0o644); err != nil {
		t.Fatalf("테스트용 계획 파일 쓰기 실패: %v", err)
	}

	return workDirPath
}

// hasSameTask 는 두 작업이 같은 내용인지 본다.
//
// Task 는 Needs 슬라이스를 품고 있어 == 로 비교되지 않는다. Needs 는 slices.Equal 로 비교하는데,
// 이 함수는 nil 과 빈 슬라이스를 같게 본다. "needs: []" 와 needs 키 생략은 YAML 의 표현만
// 다를 뿐 선행 작업이 없다는 같은 사실이므로, 어느 쪽으로 적었는지가 검증에 끼어들면 안 된다.
func hasSameTask(left, right Task) bool {
	return left.ID == right.ID &&
		left.Repo == right.Repo &&
		left.Title == right.Title &&
		left.Status == right.Status &&
		slices.Equal(left.Needs, right.Needs)
}

func assertTasks(t *testing.T, got, want []Task) {
	t.Helper()

	if !slices.EqualFunc(got, want, hasSameTask) {
		t.Errorf("작업 목록 = %+v, want %+v", got, want)
	}
}

func TestLoadPlanParsesTasksFromDesignDocumentExample(t *testing.T) {
	workDirPath := newWorkDirWithPlanFile(t, designDocumentPlanFile)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertTasks(t, plan.Tasks, []Task{
		{ID: "commons-event", Repo: "proj-a", Title: "이벤트 클래스 추가", Needs: nil, Status: TaskStatusDone},
		{ID: "publish", Repo: "proj-b", Title: "발행 로직 구현", Needs: []string{"commons-event"}, Status: TaskStatusReady},
		{ID: "consume", Repo: "proj-c", Title: "컨슈머 구현", Needs: []string{"commons-event"}, Status: TaskStatusBlocked},
	})
}

func TestLoadPlanKeepsTaskOrderAsWritten(t *testing.T) {
	// 계획을 적은 사람은 작업을 순서대로 늘어놓는다. 그 순서가 곧 읽는 순서이므로
	// 도구가 id 순으로 정렬해버리면 사람이 의도한 흐름이 사라진다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: zeta
    repo: proj-z
    title: 마지막 작업
    status: blocked
  - id: alpha
    repo: proj-a
    title: 첫 작업
    status: ready
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	gotIDs := make([]string, 0, len(plan.Tasks))
	for _, task := range plan.Tasks {
		gotIDs = append(gotIDs, task.ID)
	}
	if !slices.Equal(gotIDs, []string{"zeta", "alpha"}) {
		t.Errorf("작업 id 순서 = %v, 파일에 적힌 순서 [zeta alpha] 를 기대", gotIDs)
	}
}
