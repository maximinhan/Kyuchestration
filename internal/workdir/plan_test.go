package workdir

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
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

func TestLoadPlanParsesFileSavedWithWindowsLineEndings(t *testing.T) {
	// 계획 파일은 도구가 만들고 끝나는 파일이 아니라 사람이 자기 에디터로 고치는 파일이라
	// CRLF 로 저장될 수 있다. 그때 구분자 줄은 "---\r" 이 되어 "---" 와의 비교가 어긋나고,
	// 계획 전체가 "frontmatter 없음" 으로 사라진다.
	workDirPath := newWorkDirWithPlanFile(t, strings.ReplaceAll(designDocumentPlanFile, "\n", "\r\n"))

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}
	if len(plan.Warnings) != 0 {
		t.Fatalf("경고 = %v, 줄 끝 문자만 다른 같은 계획이므로 경고 없음을 기대", plan.Warnings)
	}

	// 값에 \r 이 남으면 needs 가 가리키는 id 와 다른 작업의 id 가 어긋나 검증에서 걸러진다.
	// 위에서 경고가 없음을 확인했으므로 여기서는 값 자체가 깨끗한지만 본다.
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

// assertNoPlan 은 계획이 없다는 결과인지 본다. 계획이 깨진 경우와 달리 경고도 없어야 한다.
func assertNoPlan(t *testing.T, plan Plan) {
	t.Helper()

	if len(plan.Tasks) != 0 {
		t.Errorf("작업 목록 = %+v, 계획 없음을 기대", plan.Tasks)
	}
	if len(plan.Warnings) != 0 {
		t.Errorf("경고 = %v, 계획이 없는 것은 경고할 일이 아니므로 경고 없음을 기대", plan.Warnings)
	}
}

// assertPlanDroppedWithWarning 은 계획을 버리고 그 이유를 경고로 남겼는지 본다.
//
// 경고 문구 전체를 비교하지 않고 조각만 확인한다. 문구는 사람에게 보이는 문장이라 다듬어질 수
// 있지만, 사용자가 파일의 어디를 고쳐야 하는지 알아내는 데 필요한 조각은 남아있어야 한다.
func assertPlanDroppedWithWarning(t *testing.T, plan Plan, wantWarningFragments ...string) {
	t.Helper()

	if len(plan.Tasks) != 0 {
		t.Errorf("작업 목록 = %+v, 깨진 계획은 통째로 버려지기를 기대", plan.Tasks)
	}
	if len(plan.Warnings) == 0 {
		t.Fatal("경고가 없음. 계획을 버렸다면 이유를 알려야 한다")
	}

	for _, warning := range plan.Warnings {
		// 경고 하나는 한 줄이다. 호출부가 경고마다 붙이는 들여쓰기나 접두사가 둘째 줄부터
		// 어긋나므로, 여러 줄짜리 라이브러리 메시지를 그대로 담아서는 안 된다.
		if strings.Contains(warning, "\n") {
			t.Errorf("경고에 줄바꿈이 들어있음: %q", warning)
		}
	}

	joinedWarnings := strings.Join(plan.Warnings, "\n")
	for _, wantFragment := range wantWarningFragments {
		if !strings.Contains(joinedWarnings, wantFragment) {
			t.Errorf("경고 = %q, %q 를 포함하기를 기대", joinedWarnings, wantFragment)
		}
	}
}

func TestLoadPlanReturnsNoPlanWhenPlanFileIsMissing(t *testing.T) {
	// kyu init 을 아직 하지 않았거나 조율할 것이 없는 워크디렉토리다. 흔한 정상 상태이므로
	// 에러가 아니다 — 여기서 에러를 올리면 계획과 무관한 레포 목록조차 볼 수 없다.
	plan, err := LoadPlan(t.TempDir())
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v, 계획 파일이 없는 것은 에러가 아니다", err)
	}

	assertNoPlan(t, plan)
}

func TestLoadPlanReturnsNoPlanWhenFileHasBodyWithoutFrontMatter(t *testing.T) {
	// 사람이 메모만 적어둔 계획 파일이다. 도구가 읽을 것이 없을 뿐 잘못된 파일이 아니다.
	workDirPath := newWorkDirWithPlanFile(t, `## 배경

아직 작업을 쪼개지 않았다. 인터페이스부터 정하는 중.
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertNoPlan(t, plan)
}

func TestLoadPlanReturnsNoPlanWhenFileIsEmpty(t *testing.T) {
	plan, err := LoadPlan(newWorkDirWithPlanFile(t, ""))
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertNoPlan(t, plan)
}

func TestLoadPlanReturnsNoPlanWhenFrontMatterHasNoTasks(t *testing.T) {
	// 계획 파일의 뼈대만 만들어두고 아직 작업을 적지 않은 상태다.
	plan, err := LoadPlan(newWorkDirWithPlanFile(t, "---\n---\n\n## 배경\n"))
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertNoPlan(t, plan)
}

func TestLoadPlanWarnsWhenFrontMatterIsNotTerminated(t *testing.T) {
	// 여는 --- 를 적었다면 계획을 적으려던 것이다. 닫는 --- 를 빠뜨린 것을 "계획 없음" 으로
	// 흘려보내면 사용자는 자기가 적어둔 작업이 왜 안 보이는지 알 수 없다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    status: ready

## 배경
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v, 깨진 계획은 에러가 아니라 경고여야 한다", err)
	}

	assertPlanDroppedWithWarning(t, plan, "---")
}

func TestLoadPlanWarnsWhenFrontMatterYAMLIsBroken(t *testing.T) {
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: publish
    repo: [닫히지 않은 목록
    title: 발행 로직 구현
    status: ready
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v, 깨진 계획은 에러가 아니라 경고여야 한다", err)
	}

	assertPlanDroppedWithWarning(t, plan, "yaml")
}

func TestLoadPlanWarningNamesThePlanFilePath(t *testing.T) {
	// 경고는 호출부가 그대로 출력한다. 어느 파일을 고쳐야 하는지가 문장 안에 없으면
	// 사용자는 워크디렉토리를 뒤지게 된다.
	workDirPath := newWorkDirWithPlanFile(t, "---\ntasks: [닫히지 않은 목록\n---\n")

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertPlanDroppedWithWarning(t, plan, filepath.Join(workDirPath, ".coord", "plan.md"))
}

func TestLoadPlanPropagatesReadFailure(t *testing.T) {
	// 파일이 거기 있는데 읽지 못한 것은 관찰 자체가 실패한 것이라 "계획 없음" 과 다르다.
	// 없는 계획으로 뭉뚱그리면 사용자는 자기가 적어둔 계획이 왜 사라졌는지 알 길이 없다.
	workDirPath := newWorkDirWithPlanFile(t, designDocumentPlanFile)
	planFilePath := filepath.Join(workDirPath, ".coord", "plan.md")

	if err := os.Chmod(planFilePath, 0o000); err != nil {
		t.Skipf("권한을 바꿀 수 없어 건너뜁니다: %v", err)
	}
	// root 로 실행하면 권한 비트를 무시하고 읽는다. Windows 에서도 이렇게는 읽기를 막지 못한다.
	// 그런 환경에서 이 테스트는 아무것도 검증하지 못하므로 실패가 아니라 건너뛴다.
	if _, err := os.ReadFile(planFilePath); err == nil {
		t.Skip("이 환경에서는 파일 읽기를 막을 수 없어 건너뜁니다")
	}

	if _, err := LoadPlan(workDirPath); err == nil {
		t.Fatal("LoadPlan() 이 에러 없이 끝남. 읽기 실패는 전파되어야 한다")
	}
}

func TestLoadPlanWarnsWhenRequiredTaskFieldIsMissing(t *testing.T) {
	testCases := []struct {
		name                string
		planFileContent     string
		wantWarningFragment string
	}{
		{
			name: "id 없음",
			planFileContent: `---
tasks:
  - repo: proj-b
    title: 발행 로직 구현
    status: ready
---
`,
			wantWarningFragment: "id",
		},
		{
			name: "repo 없음",
			planFileContent: `---
tasks:
  - id: publish
    title: 발행 로직 구현
    status: ready
---
`,
			wantWarningFragment: "repo",
		},
		{
			name: "title 없음",
			planFileContent: `---
tasks:
  - id: publish
    repo: proj-b
    status: ready
---
`,
			wantWarningFragment: "title",
		},
		{
			name: "status 없음",
			planFileContent: `---
tasks:
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
---
`,
			wantWarningFragment: "status",
		},
		{
			// 적기는 했지만 비어있는 값이다. 따옴표로 감싼 공백은 YAML 이 값으로 인정하므로
			// 필드가 있는지만 보면 통과해버린다.
			name: "id 가 공백뿐",
			planFileContent: `---
tasks:
  - id: "   "
    repo: proj-b
    title: 발행 로직 구현
    status: ready
---
`,
			wantWarningFragment: "id",
		},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			plan, err := LoadPlan(newWorkDirWithPlanFile(t, testCase.planFileContent))
			if err != nil {
				t.Fatalf("LoadPlan() 실패: %v, 필수 필드 누락은 에러가 아니라 경고여야 한다", err)
			}

			assertPlanDroppedWithWarning(t, plan, testCase.wantWarningFragment)
		})
	}
}

func TestLoadPlanWarnsWhenStatusIsNotOneOfTheDefinedValues(t *testing.T) {
	// 정의되지 않은 값을 그대로 통과시키면 어느 상태에도 해당하지 않는 작업이 목록에 섞인다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    status: reviewing
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	// 쓸 수 있는 값이 무엇인지까지 알려야 사용자가 파일을 고칠 수 있다.
	assertPlanDroppedWithWarning(t, plan, "reviewing", "blocked", "ready", "doing", "done")
}

func TestLoadPlanAcceptsEveryDefinedStatus(t *testing.T) {
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: t-blocked
    repo: proj-a
    title: 막힌 작업
    status: blocked
  - id: t-ready
    repo: proj-b
    title: 시작할 수 있는 작업
    status: ready
  - id: t-doing
    repo: proj-c
    title: 진행 중인 작업
    status: doing
  - id: t-done
    repo: proj-d
    title: 끝난 작업
    status: done
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}
	if len(plan.Warnings) != 0 {
		t.Fatalf("경고 = %v, 정의된 status 만 쓴 계획에는 경고가 없기를 기대", plan.Warnings)
	}

	assertTasks(t, plan.Tasks, []Task{
		{ID: "t-blocked", Repo: "proj-a", Title: "막힌 작업", Status: TaskStatusBlocked},
		{ID: "t-ready", Repo: "proj-b", Title: "시작할 수 있는 작업", Status: TaskStatusReady},
		{ID: "t-doing", Repo: "proj-c", Title: "진행 중인 작업", Status: TaskStatusDoing},
		{ID: "t-done", Repo: "proj-d", Title: "끝난 작업", Status: TaskStatusDone},
	})
}

func TestLoadPlanWarnsWhenTaskIDIsDuplicated(t *testing.T) {
	// id 가 겹치면 needs 가 어느 작업을 가리키는지 정해지지 않는다. 계획의 선행 관계가
	// 읽는 사람마다 달라지므로 통과시킬 수 없다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    status: ready
  - id: publish
    repo: proj-c
    title: 컨슈머 구현
    status: blocked
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertPlanDroppedWithWarning(t, plan, "publish", "중복")
}

func TestLoadPlanWarnsWhenNeedsPointsToUnknownTaskID(t *testing.T) {
	// 오타 하나로 선행 관계가 사라진다. 조용히 무시하면 아직 못 하는 작업이 시작 가능한
	// 작업으로 보인다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: commons-event
    repo: proj-a
    title: 이벤트 클래스 추가
    needs: []
    status: done
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    needs: [commons-evnt]
    status: ready
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertPlanDroppedWithWarning(t, plan, "commons-evnt")
}

func TestLoadPlanAcceptsNeedsPointingToLaterTask(t *testing.T) {
	// needs 는 파일에서 뒤에 오는 작업을 가리켜도 된다. 계획을 적는 사람에게 위상 순서로
	// 늘어놓으라고 요구하지 않는다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    needs: [commons-event]
    status: blocked
  - id: commons-event
    repo: proj-a
    title: 이벤트 클래스 추가
    status: done
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}
	if len(plan.Warnings) != 0 {
		t.Fatalf("경고 = %v, 뒤에 오는 작업을 가리키는 needs 는 정상이므로 경고 없음을 기대", plan.Warnings)
	}
	if len(plan.Tasks) != 2 {
		t.Errorf("작업 %d 개, 2 개를 기대", len(plan.Tasks))
	}
}

func TestLoadPlanWarnsWhenTaskHasUnknownField(t *testing.T) {
	// 한 글자 틀린 키를 조용히 버리면 선행 관계가 통째로 사라진 계획이 아무 말 없이
	// 그럴듯하게 표시된다. 쓸 수 있는 필드는 설계 문서 6.1 의 표가 전부다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    neeeds: [commons-event]
    status: ready
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertPlanDroppedWithWarning(t, plan, "neeeds")
}

func TestLoadPlanReportsEveryProblemAtOnce(t *testing.T) {
	// 계획을 고치는 사람은 파일을 열어 한 번에 고친다. 문제를 하나씩만 알려주면
	// 고치고 다시 돌리기를 문제 수만큼 반복하게 된다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: commons-event
    repo: proj-a
    status: done
  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    status: reviewing
  - id: consume
    repo: proj-c
    title: 컨슈머 구현
    needs: [publish, 없는-작업]
    status: blocked
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertPlanDroppedWithWarning(t, plan, "title", "reviewing", "없는-작업")
	if len(plan.Warnings) != 3 {
		t.Errorf("경고 %d 개 = %v, 문제 3 개가 각각 보고되기를 기대", len(plan.Warnings), plan.Warnings)
	}
}

func TestLoadPlanWarningPointsAtTheTaskByPositionAndID(t *testing.T) {
	// 작업이 여러 개인 계획에서 "필수 필드가 없다" 만으로는 어느 작업인지 찾을 수 없다.
	workDirPath := newWorkDirWithPlanFile(t, `---
tasks:
  - id: commons-event
    repo: proj-a
    title: 이벤트 클래스 추가
    status: done
  - id: publish
    repo: proj-b
    status: ready
---
`)

	plan, err := LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}

	assertPlanDroppedWithWarning(t, plan, "2", "publish", "title")
}
