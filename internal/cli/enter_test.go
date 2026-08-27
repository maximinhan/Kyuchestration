package cli

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/session"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// assertOnlyAttachedSession 은 Attach 가 정확히 그 세션 하나에만 불렸는지 본다.
//
// 진입 플로우의 마지막 한 걸음은 자동 테스트로 끝까지 따라갈 수 없다 — 실제 Attach 는 TTY 를
// 잡고 사용자가 빠져나올 때까지 돌아오지 않는다(attach_test.go 와 같은 이유). 여기서 고정할 수
// 있는 것은 "무엇에 붙으려 했는가" 까지이고, 그 자리를 백엔드 경계에서 못박는다.
func assertOnlyAttachedSession(t *testing.T, backend *recordingSessionBackend, wantSessionName string) {
	t.Helper()

	if want := []string{wantSessionName}; !slices.Equal(backend.attachedSessionNames, want) {
		t.Errorf("진입한 세션 = %q, want %q", backend.attachedSessionNames, want)
	}
}

func TestEnterWorkDirCreatesTheMainSessionWithAddDirAndThenEntersIt(t *testing.T) {
	// 이 도구를 쓰는 사람의 머릿속에서 kyu 는 "이 디렉토리에서 작업을 시작한다" 한 동작이다.
	// 초기화·생성·진입 세 걸음이 한 번에 이어지는지를 여기서 고정한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "beta-gateway")
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out, errOut bytes.Buffer
	if err := EnterWorkDir(&out, &errOut, backend); err != nil {
		t.Fatalf("EnterWorkDir() 실패: %v", err)
	}

	assertOnlyCreatedSession(t, backend, createdSession{
		name: "kyu-WorkDir-featureX-main",
		cwd:  workDirPath,
		command: []string{
			"claude",
			"--add-dir", filepath.Join(workDirPath, "alpha-commons"),
			"--add-dir", filepath.Join(workDirPath, "beta-gateway"),
		},
	})
	assertOnlyAttachedSession(t, backend, "kyu-WorkDir-featureX-main")

	if !strings.Contains(out.String(), detachKeyBinding) {
		t.Errorf("출력 = %q, 빠져나오는 방법 안내를 포함하기를 기대", out.String())
	}
}

func TestEnterWorkDirEntersTheRunningMainSessionWithoutCreatingItAgain(t *testing.T) {
	// kyu 는 몇 번을 쳐도 같은 결과여야 한다. 이미 떠 있는데 또 만들려 들면 그 시도는
	// 이미 존재한다는 안내로 끝나고, 사용자는 진입 대신 안내문을 읽게 된다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("kyu-WorkDir-featureX-main")

	var out, errOut bytes.Buffer
	if err := EnterWorkDir(&out, &errOut, backend); err != nil {
		t.Fatalf("EnterWorkDir() 실패: %v", err)
	}

	if len(backend.createdSessions) != 0 {
		t.Errorf("Create 호출 = %+v, 이미 떠 있으면 만들지 않기를 기대", backend.createdSessions)
	}
	assertOnlyAttachedSession(t, backend, "kyu-WorkDir-featureX-main")
}

func TestEnterWorkDirInitializesTheWorkDirWhenThePlanFileIsMissing(t *testing.T) {
	// cd 한 디렉토리에서 kyu 한 번이면 된다는 것이 이 명령의 약속이라, 초기화를 따로 시키지 않는다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out, errOut bytes.Buffer
	if err := EnterWorkDir(&out, &errOut, backend); err != nil {
		t.Fatalf("EnterWorkDir() 실패: %v", err)
	}

	planFilePath := workdir.PlanFilePath(workDirPath)
	if _, err := os.Stat(planFilePath); err != nil {
		t.Fatalf("계획 파일이 만들어지지 않았습니다: %v", err)
	}

	// 조용히 만들면 사용자는 자기 디렉토리에 무엇이 생겼는지 모른 채 세션 안으로 들어간다.
	if !strings.Contains(out.String(), planFilePath) {
		t.Errorf("출력 = %q, 만들어진 계획 파일 경로 %s 를 알리기를 기대", out.String(), planFilePath)
	}
}

func TestEnterWorkDirKeepsThePlanFileThatIsAlreadyThere(t *testing.T) {
	// 계획 파일은 사람이 쓴 글이다. 진입할 때마다 손대면 되돌릴 수 없다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	const handWrittenPlan = "---\ntasks:\n---\n\n사람이 쓴 계획\n"
	writePlanFileForTest(t, workDirPath, handWrittenPlan)

	backend := newRecordingSessionBackend()

	var out, errOut bytes.Buffer
	if err := EnterWorkDir(&out, &errOut, backend); err != nil {
		t.Fatalf("EnterWorkDir() 실패: %v", err)
	}

	planFileContent, err := os.ReadFile(workdir.PlanFilePath(workDirPath))
	if err != nil {
		t.Fatalf("계획 파일 읽기 실패: %v", err)
	}
	if string(planFileContent) != handWrittenPlan {
		t.Errorf("계획 파일 = %q, 사람이 쓴 내용 %q 그대로이기를 기대", planFileContent, handWrittenPlan)
	}
	if strings.Contains(out.String(), "초기화") {
		t.Errorf("출력 = %q, 이미 초기화된 워크디렉토리에서는 초기화를 알리지 않기를 기대", out.String())
	}
}

func TestEnterWorkDirTranslatesNestedSessionRefusalIntoGuidance(t *testing.T) {
	// 세션 안에서 kyu 를 또 치는 것은 흔한 실수다. 백엔드의 "중첩이다" 를 사용자가 할 수 있는
	// 행동으로 옮기는 자리가 진입 플로우에도 있어야 한다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("kyu-WorkDir-featureX-main")
	backend.attachError = fmt.Errorf("%w: kyu-WorkDir-featureX-main", session.ErrNestedSession)

	var out, errOut bytes.Buffer
	err := EnterWorkDir(&out, &errOut, backend)

	if err == nil {
		t.Fatalf("EnterWorkDir() 가 에러를 반환하지 않음, 중첩 거절을 기대")
	}
	if !strings.Contains(err.Error(), detachKeyBinding) {
		t.Errorf("에러 = %v, 먼저 빠져나오는 방법 안내를 포함하기를 기대", err)
	}
}

func TestEnterWorkDirRefusesToTurnTheHomeDirectoryIntoAWorkDir(t *testing.T) {
	// cd 를 한 번 빠뜨리면 홈에서 실행하게 된다. 그 한 번으로 홈 전체가 워크디렉토리가 되면
	// 홈 아래 모든 레포가 --add-dir 로 붙고 .coord/plan.md 가 홈에 남는다.
	homeDirectoryPath := makeWorkDir(t)
	t.Setenv("HOME", homeDirectoryPath)
	t.Chdir(homeDirectoryPath)

	backend := newRecordingSessionBackend()

	var out, errOut bytes.Buffer
	err := EnterWorkDir(&out, &errOut, backend)

	if err == nil {
		t.Fatalf("EnterWorkDir() 가 에러를 반환하지 않음, 홈 디렉토리 거절을 기대")
	}
	// 길을 막는 것이 아니라 손으로 한 번 더 말하게 하는 것이므로, 그 방법을 함께 알린다.
	if !strings.Contains(err.Error(), "kyu init") {
		t.Errorf("에러 = %v, 명시적 초기화 방법 안내를 포함하기를 기대", err)
	}

	if _, statErr := os.Stat(workdir.PlanFilePath(homeDirectoryPath)); statErr == nil {
		t.Errorf("홈 디렉토리에 계획 파일이 만들어졌습니다: %s", workdir.PlanFilePath(homeDirectoryPath))
	}
	if len(backend.createdSessions) != 0 {
		t.Errorf("Create 호출 = %+v, 거절했으면 세션도 만들지 않기를 기대", backend.createdSessions)
	}
}

func TestEnterWorkDirEntersTheHomeDirectoryThatWasInitializedByHand(t *testing.T) {
	// 거절하는 것은 자동 초기화뿐이다. 사용자가 kyu init 으로 직접 선언한 자리라면 그 뜻이 이미
	// 분명하므로 막지 않는다 — 실수를 막자고 사용자가 내린 결정을 되돌리지는 않는다.
	homeDirectoryPath := makeWorkDir(t)
	t.Setenv("HOME", homeDirectoryPath)
	t.Chdir(homeDirectoryPath)
	writePlanFileForTest(t, homeDirectoryPath, "---\ntasks:\n---\n")

	backend := newRecordingSessionBackend()

	var out, errOut bytes.Buffer
	if err := EnterWorkDir(&out, &errOut, backend); err != nil {
		t.Fatalf("EnterWorkDir() 실패: %v", err)
	}

	assertOnlyAttachedSession(t, backend, "kyu-"+filepath.Base(homeDirectoryPath)+"-main")
}

func TestEnterWorkDirWarnsThatReposClonedInsideTheSessionNeedARestart(t *testing.T) {
	// 레포가 하나도 없어도 진입은 막지 않는다 — 무엇을 클론할지 정하는 것부터가 메인 세션의 일이다.
	// 다만 --add-dir 목록은 세션을 만드는 순간 굳으므로, 그 사실을 진입 전에 알린다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out, errOut bytes.Buffer
	if err := EnterWorkDir(&out, &errOut, backend); err != nil {
		t.Fatalf("EnterWorkDir() 실패: %v", err)
	}

	if !strings.Contains(errOut.String(), "kyu kill main") {
		t.Errorf("stderr = %q, 세션을 다시 띄우는 방법 안내를 포함하기를 기대", errOut.String())
	}
	// 경고는 stdout 을 오염시키지 않는다 — 진입 안내와 목록 출력만 stdout 의 몫이다.
	if strings.Contains(out.String(), "kyu kill main") {
		t.Errorf("stdout = %q, 경고는 stderr 로만 나가기를 기대", out.String())
	}
	assertOnlyAttachedSession(t, backend, "kyu-WorkDir-featureX-main")
}

func TestEnterWorkDirDoesNotWarnAboutMissingReposWhenTheSessionIsAlreadyRunning(t *testing.T) {
	// 경고의 내용이 "지금 만드는 세션에 무엇이 붙는가" 라서, 만들지 않는 실행에서는 할 말이 없다.
	// 진입할 때마다 되풀이하면 읽히지 않는 경고가 된다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("kyu-WorkDir-featureX-main")

	var out, errOut bytes.Buffer
	if err := EnterWorkDir(&out, &errOut, backend); err != nil {
		t.Fatalf("EnterWorkDir() 실패: %v", err)
	}

	if errOut.Len() != 0 {
		t.Errorf("stderr = %q, 세션을 만들지 않는 실행에서는 경고하지 않기를 기대", errOut.String())
	}
}
