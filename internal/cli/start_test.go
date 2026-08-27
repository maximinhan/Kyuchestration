package cli

import (
	"bytes"
	"errors"
	"fmt"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

// assertOnlyCreatedSession 은 Create 가 정확히 한 번 불렸고 그 인자가 기대와 같은지 본다.
//
// 세션 이름·cwd·명령을 따로 뜯어 비교하지 않고 한 번에 본다. 셋 중 하나만 맞아도 세션은 뜨지만
// 엉뚱한 디렉토리에서 엉뚱한 것이 뜬다 — 사용자에게는 셋이 한 덩어리다.
func assertOnlyCreatedSession(t *testing.T, backend *recordingSessionBackend, want createdSession) {
	t.Helper()

	if len(backend.createdSessions) != 1 {
		t.Fatalf("Create 호출 = %+v, 정확히 한 번 불리기를 기대", backend.createdSessions)
	}

	got := backend.createdSessions[0]
	if got.name != want.name || got.cwd != want.cwd || !slices.Equal(got.command, want.command) {
		t.Errorf("Create 인자가 다릅니다.\n--- got ---\n%+v\n--- want ---\n%+v", got, want)
	}
}

func TestStartSessionWithoutRepoCreatesMainSessionWithAddDirForEveryRepo(t *testing.T) {
	// 메인 세션의 존재 이유가 이 조립이다(설계 문서 5.4). 레포를 하나라도 빠뜨리면 메인은 그 레포를
	// 읽지 못한 채 계획을 세우고, 사용자는 그 사실을 세션 안에서야 알게 된다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "zeta-service")
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "beta-gateway")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	if err := StartSession(&out, nil, backend); err != nil {
		t.Fatalf("StartSession() 실패: %v", err)
	}

	assertOnlyCreatedSession(t, backend, createdSession{
		name: "wd-WorkDir-featureX-main",
		cwd:  workDirPath,
		command: []string{
			"claude",
			"--add-dir", filepath.Join(workDirPath, "alpha-commons"),
			"--add-dir", filepath.Join(workDirPath, "beta-gateway"),
			"--add-dir", filepath.Join(workDirPath, "zeta-service"),
		},
	})

	if !strings.Contains(out.String(), "wd attach main") {
		t.Errorf("출력 = %q, 진입 방법 안내를 포함하기를 기대", out.String())
	}
}

func TestStartSessionWithoutRepoInEmptyWorkDirCreatesMainSessionWithoutAddDir(t *testing.T) {
	// 레포가 아직 없어도 메인은 뜬다. 클론할 레포를 정하는 것부터가 메인이 할 일이다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	if err := StartSession(&out, nil, backend); err != nil {
		t.Fatalf("StartSession() 실패: %v", err)
	}

	assertOnlyCreatedSession(t, backend, createdSession{
		name:    "wd-WorkDir-featureX-main",
		cwd:     workDirPath,
		command: []string{"claude"},
	})
}

func TestStartSessionWithRepoCreatesSessionInThatRepoDirectory(t *testing.T) {
	// 레포 세션이 그 레포의 MCP·CLAUDE.md·에이전트를 그대로 쓰는 근거는 cwd 하나뿐이다(설계 문서 1.3).
	// --add-dir 로 붙이는 것과 cwd 로 여는 것의 차이가 이 도구가 존재하는 이유다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "beta-gateway")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	if err := StartSession(&out, []string{"beta-gateway"}, backend); err != nil {
		t.Fatalf("StartSession() 실패: %v", err)
	}

	assertOnlyCreatedSession(t, backend, createdSession{
		name:    "wd-WorkDir-featureX-beta-gateway",
		cwd:     filepath.Join(workDirPath, "beta-gateway"),
		command: []string{"claude"},
	})

	if !strings.Contains(out.String(), "wd attach beta-gateway") {
		t.Errorf("출력 = %q, 진입 방법 안내를 포함하기를 기대", out.String())
	}
}

func TestStartSessionOnUnknownRepoListsTheReposItFoundAndCreatesNothing(t *testing.T) {
	// 이름을 잘못 적었을 때 "그런 레포 없음" 만 알려주면 사용자는 정확한 이름을 찾으러 다시 나가야 한다.
	// 이미 스캔한 목록을 그 자리에서 보여준다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "beta-gateway")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	err := StartSession(&out, []string{"gamma-worker"}, backend)

	if err == nil {
		t.Fatalf("StartSession() 가 에러를 반환하지 않음, 없는 레포 에러를 기대")
	}
	for _, wantRepoName := range []string{"alpha-commons", "beta-gateway"} {
		if !strings.Contains(err.Error(), wantRepoName) {
			t.Errorf("에러 = %v, 실제 레포 이름 %q 를 포함하기를 기대", err, wantRepoName)
		}
	}
	if len(backend.createdSessions) != 0 {
		t.Errorf("Create 호출 = %+v, 없는 레포에는 세션을 만들지 않기를 기대", backend.createdSessions)
	}
}

func TestStartSessionGuidesToAttachWhenTheSessionIsAlreadyRunning(t *testing.T) {
	// 이미 떠 있는 것은 사용자가 원한 상태와 같다. 실패로 끝내면 wd start 를 앞에 둔 스크립트가
	// 두 번째 실행부터 깨진다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()
	backend.createError = fmt.Errorf("%w: wd-WorkDir-featureX-alpha-commons", session.ErrSessionExists)

	var out bytes.Buffer
	if err := StartSession(&out, []string{"alpha-commons"}, backend); err != nil {
		t.Fatalf("StartSession() = %v, 이미 실행 중인 세션은 성공으로 끝나기를 기대", err)
	}

	if !strings.Contains(out.String(), "이미 실행 중") {
		t.Errorf("출력 = %q, 이미 실행 중이라는 안내를 기대", out.String())
	}
	if !strings.Contains(out.String(), "wd attach alpha-commons") {
		t.Errorf("출력 = %q, 진입 방법 안내를 포함하기를 기대", out.String())
	}
}

func TestStartSessionPropagatesCreateFailureOtherThanSessionExists(t *testing.T) {
	// ErrSessionExists 만 성공으로 흡수한다. tmux 가 뜨지 않는 것 같은 진짜 실패까지 삼키면
	// 사용자는 세션이 떴다고 믿은 채 attach 에서야 알게 된다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()
	backend.createError = errors.New("tmux 서버를 띄우지 못했습니다")

	var out bytes.Buffer
	if err := StartSession(&out, nil, backend); err == nil {
		t.Fatalf("StartSession() 가 에러를 반환하지 않음, 세션 생성 실패 전파를 기대")
	}
}

func TestStartSessionRejectsMoreThanOneRepo(t *testing.T) {
	// 두 개를 받고 하나만 띄우면 사용자는 나머지 하나도 떴다고 믿는다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	if err := StartSession(&out, []string{"alpha-commons", "beta-gateway"}, backend); err == nil {
		t.Fatalf("StartSession() 가 에러를 반환하지 않음, 인자 개수 에러를 기대")
	}
	if len(backend.createdSessions) != 0 {
		t.Errorf("Create 호출 = %+v, 인자가 잘못됐으면 아무것도 만들지 않기를 기대", backend.createdSessions)
	}
}

func TestStartSessionRejectsUnknownOption(t *testing.T) {
	// 모르는 옵션을 레포 이름으로 착각해 넘기면 "없는 레포" 라는 엉뚱한 안내가 나온다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	err := StartSession(&out, []string{"--없는옵션"}, backend)

	if err == nil {
		t.Fatalf("StartSession() 가 에러를 반환하지 않음, 알 수 없는 옵션 에러를 기대")
	}
	if !strings.Contains(err.Error(), "--없는옵션") {
		t.Errorf("에러 = %v, 문제의 옵션을 그대로 되짚어 주기를 기대", err)
	}
}

func TestStartSessionWithRepoClaudeMdOptionWrapsTheCommandInEnvAssignment(t *testing.T) {
	// 추가 디렉토리의 CLAUDE.md 는 이 환경변수가 1 일 때만 로드된다(설계 문서 5.4).
	// 백엔드 인터페이스에 환경변수 인자를 새로 뚫는 대신 명령을 env 로 감싼다 — 세션 계층은
	// 플랫폼 의존부라 넓힐수록 다른 플랫폼 구현이 따라 해야 할 것이 늘어난다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	if err := StartSession(&out, []string{"--repo-claude-md"}, backend); err != nil {
		t.Fatalf("StartSession() 실패: %v", err)
	}

	assertOnlyCreatedSession(t, backend, createdSession{
		name: "wd-WorkDir-featureX-main",
		cwd:  workDirPath,
		command: []string{
			"env", "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1",
			"claude",
			"--add-dir", filepath.Join(workDirPath, "alpha-commons"),
		},
	})
}

func TestStartSessionRejectsRepoClaudeMdOptionForARepoSession(t *testing.T) {
	// 레포 세션은 자기 디렉토리에서 뜨므로 그 레포의 CLAUDE.md 를 이미 읽는다. 옵션을 조용히
	// 무시하면 사용자는 무언가 켜졌다고 믿고, 그 믿음은 계획이 어긋날 때까지 드러나지 않는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	err := StartSession(&out, []string{"alpha-commons", "--repo-claude-md"}, backend)

	if err == nil {
		t.Fatalf("StartSession() 가 에러를 반환하지 않음, 메인 세션 전용 옵션 에러를 기대")
	}
	if !strings.Contains(err.Error(), "메인 세션") {
		t.Errorf("에러 = %v, 이 옵션이 메인 세션 전용이라는 이유를 알려주기를 기대", err)
	}
	if len(backend.createdSessions) != 0 {
		t.Errorf("Create 호출 = %+v, 옵션이 잘못됐으면 아무것도 만들지 않기를 기대", backend.createdSessions)
	}
}
