package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// readPlanFile 은 초기화된 워크디렉토리의 계획 파일을 읽는다.
func readPlanFile(t *testing.T, workDirPath string) string {
	t.Helper()

	content, err := os.ReadFile(filepath.Join(workDirPath, ".coord", "plan.md"))
	if err != nil {
		t.Fatalf("계획 파일 읽기 실패: %v", err)
	}
	return string(content)
}

func TestInitWorkDirCreatesPlanFileInCurrentDirectoryWhenNoNameGiven(t *testing.T) {
	workDirPath := t.TempDir()
	t.Chdir(workDirPath)

	var out bytes.Buffer
	if err := InitWorkDir(&out, nil); err != nil {
		t.Fatalf("InitWorkDir() 실패: %v", err)
	}

	if got := readPlanFile(t, workDirPath); got != planFileTemplate {
		t.Errorf("계획 파일 내용 = %q, 템플릿 그대로이기를 기대", got)
	}
}

func TestInitWorkDirCreatesNamedDirectoryWhenNameGiven(t *testing.T) {
	parentPath := t.TempDir()
	t.Chdir(parentPath)

	var out bytes.Buffer
	if err := InitWorkDir(&out, []string{"WorkDir-featureX"}); err != nil {
		t.Fatalf("InitWorkDir() 실패: %v", err)
	}

	readPlanFile(t, filepath.Join(parentPath, "WorkDir-featureX"))
}

func TestInitWorkDirKeepsWhatIsAlreadyInAnExistingDirectory(t *testing.T) {
	// 이름을 받았을 때 디렉토리가 이미 있으면 그대로 쓴다. 레포를 먼저 클론해두고 나중에
	// 초기화하는 순서가 자연스럽기 때문이다. 있던 것을 지우면 그 순서가 성립하지 않는다.
	parentPath := t.TempDir()
	t.Chdir(parentPath)

	existingRepoPath := filepath.Join(parentPath, "WorkDir-featureX", "proj-a")
	if err := os.MkdirAll(existingRepoPath, 0o755); err != nil {
		t.Fatalf("테스트 준비 실패: %v", err)
	}

	var out bytes.Buffer
	if err := InitWorkDir(&out, []string{"WorkDir-featureX"}); err != nil {
		t.Fatalf("InitWorkDir() 실패: %v", err)
	}

	if _, err := os.Stat(existingRepoPath); err != nil {
		t.Errorf("이미 있던 디렉토리가 사라졌습니다: %v", err)
	}
	readPlanFile(t, filepath.Join(parentPath, "WorkDir-featureX"))
}

func TestInitWorkDirRefusesToOverwriteAnExistingPlanFile(t *testing.T) {
	// 계획 파일은 사람이 쓴 글이다. 도구가 덮어쓰면 되돌릴 방법이 없다.
	workDirPath := t.TempDir()
	t.Chdir(workDirPath)

	handWrittenPlan := "사람이 직접 쓴 계획\n"
	if err := os.MkdirAll(filepath.Join(workDirPath, ".coord"), 0o755); err != nil {
		t.Fatalf("테스트 준비 실패: %v", err)
	}
	if err := os.WriteFile(filepath.Join(workDirPath, ".coord", "plan.md"), []byte(handWrittenPlan), 0o644); err != nil {
		t.Fatalf("테스트 준비 실패: %v", err)
	}

	var out bytes.Buffer
	err := InitWorkDir(&out, nil)

	if err == nil {
		t.Fatalf("InitWorkDir() 가 에러를 반환하지 않음, 덮어쓰기 거절을 기대")
	}
	if got := readPlanFile(t, workDirPath); got != handWrittenPlan {
		t.Errorf("계획 파일 내용 = %q, 손대지 않기를 기대 (%q)", got, handWrittenPlan)
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 아무것도 만들지 않았으면 성공 안내를 쓰지 않기를 기대", out.String())
	}
}

func TestInitWorkDirRejectsMoreThanOneName(t *testing.T) {
	var out bytes.Buffer
	err := InitWorkDir(&out, []string{"첫-번째", "두-번째"})

	if err == nil {
		t.Fatalf("InitWorkDir() 가 에러를 반환하지 않음, 인자 개수 에러를 기대")
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 인자가 잘못됐으면 아무것도 쓰지 않기를 기대", out.String())
	}
}

func TestInitWorkDirTemplateIsReadAsNoPlan(t *testing.T) {
	// 템플릿의 예시는 전부 주석이므로 계획이 아니다. 이것이 깨지면 갓 초기화한 워크디렉토리에서
	// kyu list 가 있지도 않은 작업을 보여주거나 경고를 뿜는다.
	workDirPath := t.TempDir()
	t.Chdir(workDirPath)

	var out bytes.Buffer
	if err := InitWorkDir(&out, nil); err != nil {
		t.Fatalf("InitWorkDir() 실패: %v", err)
	}

	plan, err := workdir.LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}
	if len(plan.Tasks) != 0 {
		t.Errorf("Tasks = %+v, 빈 계획을 기대", plan.Tasks)
	}
	if len(plan.Warnings) != 0 {
		t.Errorf("Warnings = %q, 경고 없음을 기대", plan.Warnings)
	}
}

func TestInitWorkDirTemplateExampleBecomesAValidPlanWhenUncommented(t *testing.T) {
	// 템플릿의 값어치는 "이대로 따라 쓰면 된다" 이다. 예시가 실제로 파싱되는 형식인지 고정하지 않으면
	// 도구가 틀린 형식을 가르치게 되고, 사용자는 자기 오타를 의심하며 시간을 쓴다.
	//
	// 예시 줄만 들여쓰기(#  ) 로 주석 처리하고 설명 줄은 # + 한 칸으로 두었으므로,
	// "\n#  " 를 "\n  " 로 바꾸는 것이 곧 사용자가 손으로 하는 주석 풀기와 같다.
	workDirPath := t.TempDir()
	t.Chdir(workDirPath)

	var out bytes.Buffer
	if err := InitWorkDir(&out, nil); err != nil {
		t.Fatalf("InitWorkDir() 실패: %v", err)
	}

	planFilePath := filepath.Join(workDirPath, ".coord", "plan.md")
	uncommented := strings.ReplaceAll(readPlanFile(t, workDirPath), "\n#  ", "\n  ")
	if err := os.WriteFile(planFilePath, []byte(uncommented), 0o644); err != nil {
		t.Fatalf("테스트 준비 실패: %v", err)
	}

	plan, err := workdir.LoadPlan(workDirPath)
	if err != nil {
		t.Fatalf("LoadPlan() 실패: %v", err)
	}
	if len(plan.Warnings) != 0 {
		t.Fatalf("Warnings = %q, 예시는 경고 없이 읽히기를 기대", plan.Warnings)
	}

	want := []workdir.Task{
		{ID: "commons-event", Repo: "proj-a", Title: "이벤트 클래스 추가", Needs: []string{}, Status: workdir.TaskStatusDone},
		{ID: "publish", Repo: "proj-b", Title: "발행 로직 구현", Needs: []string{"commons-event"}, Status: workdir.TaskStatusReady},
		{ID: "consume", Repo: "proj-c", Title: "컨슈머 구현", Needs: []string{"commons-event"}, Status: workdir.TaskStatusBlocked},
	}
	if len(plan.Tasks) != len(want) {
		t.Fatalf("Tasks = %+v, want %+v", plan.Tasks, want)
	}
	for taskIndex, wantTask := range want {
		gotTask := plan.Tasks[taskIndex]
		if gotTask.ID != wantTask.ID || gotTask.Repo != wantTask.Repo || gotTask.Title != wantTask.Title || gotTask.Status != wantTask.Status {
			t.Errorf("Tasks[%d] = %+v, want %+v", taskIndex, gotTask, wantTask)
		}
		if !slices.Equal(gotTask.Needs, wantTask.Needs) {
			t.Errorf("Tasks[%d].Needs = %q, want %q", taskIndex, gotTask.Needs, wantTask.Needs)
		}
	}
}

func TestInitWorkDirRejectsAnOptionLookingName(t *testing.T) {
	// 옵션처럼 생긴 것을 이름으로 흘려보내면 --typo 라는 이름의 디렉토리가 조용히 만들어진다.
	var out bytes.Buffer
	err := InitWorkDir(&out, []string{"--전부"})

	if err == nil {
		t.Fatalf("InitWorkDir() 가 에러를 반환하지 않음, 알 수 없는 옵션 에러를 기대")
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 인자가 잘못됐으면 아무것도 쓰지 않기를 기대", out.String())
	}
}
