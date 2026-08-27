package cli

import (
	"bytes"
	"slices"
	"strings"
	"testing"
)

func TestKillSessionsEndsTheRunningSessionOfThatRepo(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("kyu-WorkDir-featureX-alpha-commons")

	var out bytes.Buffer
	if err := KillSessions(&out, []string{"alpha-commons"}, backend); err != nil {
		t.Fatalf("KillSessions() 실패: %v", err)
	}

	if want := []string{"kyu-WorkDir-featureX-alpha-commons"}; !slices.Equal(backend.killedSessionNames, want) {
		t.Errorf("종료한 세션 = %q, want %q", backend.killedSessionNames, want)
	}
	if !strings.Contains(out.String(), "종료했습니다") {
		t.Errorf("출력 = %q, 무엇을 종료했는지 알리기를 기대", out.String())
	}
}

func TestKillSessionsAcceptsMainAsTarget(t *testing.T) {
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("kyu-WorkDir-featureX-main")

	var out bytes.Buffer
	if err := KillSessions(&out, []string{"main"}, backend); err != nil {
		t.Fatalf("KillSessions() 실패: %v", err)
	}

	if want := []string{"kyu-WorkDir-featureX-main"}; !slices.Equal(backend.killedSessionNames, want) {
		t.Errorf("종료한 세션 = %q, want %q", backend.killedSessionNames, want)
	}
}

func TestKillSessionsOnAMissingSessionSaysSoAndStillSucceeds(t *testing.T) {
	// 없는 세션을 지우라는 요청은 이미 이뤄진 상태다. 실패로 끝내면 정리 스크립트가 두 번째
	// 실행부터 깨진다. 그래도 조용히 끝내지는 않는다 — 이름을 잘못 적었을 때 아무 말이 없으면
	// 사용자는 그 세션을 죽였다고 믿는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	if err := KillSessions(&out, []string{"alpha-commons"}, backend); err != nil {
		t.Fatalf("KillSessions() = %v, 없는 세션은 성공으로 끝나기를 기대", err)
	}

	if len(backend.killedSessionNames) != 0 {
		t.Errorf("종료한 세션 = %q, 없는 세션에는 손대지 않기를 기대", backend.killedSessionNames)
	}
	if !strings.Contains(out.String(), "세션이 없습니다") {
		t.Errorf("출력 = %q, 종료할 것이 없었다는 안내를 기대", out.String())
	}
}

func TestKillSessionsRequiresATarget(t *testing.T) {
	// 인자 없는 kill 을 "전부 종료" 로 해석하면, 이름을 빠뜨린 사용자가 남의 작업까지 날린다.
	tests := []struct {
		scenario string
		args     []string
	}{
		{scenario: "인자가 없으면 사용법을 보여준다", args: nil},
		{scenario: "이름을 둘 이상 받으면 거절한다", args: []string{"alpha-commons", "beta-gateway"}},
		{scenario: "모르는 옵션은 이름으로 받지 않는다", args: []string{"--없는옵션"}},
	}

	for _, tt := range tests {
		t.Run(tt.scenario, func(t *testing.T) {
			workDirPath := makeWorkDir(t)
			t.Chdir(workDirPath)

			backend := newRecordingSessionBackend("kyu-WorkDir-featureX-alpha-commons")

			var out bytes.Buffer
			if err := KillSessions(&out, tt.args, backend); err == nil {
				t.Fatalf("KillSessions(%q) 가 에러를 반환하지 않음, 인자 에러를 기대", tt.args)
			}
			if len(backend.killedSessionNames) != 0 {
				t.Errorf("종료한 세션 = %q, 인자가 잘못됐으면 아무것도 죽이지 않기를 기대", backend.killedSessionNames)
			}
		})
	}
}

func TestKillSessionsWithAllOptionEndsOnlyTheSessionsOfThisWorkDir(t *testing.T) {
	// 세션 이름에 워크디렉토리 이름이 들어 있는 이유가 이것이다(설계 문서 5.2). 필터가 느슨하면
	// 옆 워크디렉토리에서 돌던 작업과 사용자가 직접 띄운 세션까지 함께 죽는다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()
	backend.listedSessionNames = []string{
		"kyu-WorkDir-featureX-alpha-commons",
		"kyu-OtherWorkDir-alpha-commons",
		"kyu-WorkDir-featureX-main",
		"직접-띄운-세션",
	}

	var out bytes.Buffer
	if err := KillSessions(&out, []string{"--all"}, backend); err != nil {
		t.Fatalf("KillSessions() 실패: %v", err)
	}

	want := []string{"kyu-WorkDir-featureX-alpha-commons", "kyu-WorkDir-featureX-main"}
	if !slices.Equal(backend.killedSessionNames, want) {
		t.Errorf("종료한 세션 = %q, want %q", backend.killedSessionNames, want)
	}
	// 몇 개를 죽였는지는 사용자가 되짚을 수 없는 값이다 — 세션은 이미 사라졌다. 그 자리에서 알린다.
	if got := out.String(); !strings.Contains(got, "2 개") || !strings.Contains(got, "alpha-commons") {
		t.Errorf("출력 = %q, 종료한 개수와 이름을 알리기를 기대", got)
	}
}

func TestKillSessionsWithAllOptionSaysThereWasNothingToKill(t *testing.T) {
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()
	backend.listedSessionNames = []string{"kyu-OtherWorkDir-alpha-commons"}

	var out bytes.Buffer
	if err := KillSessions(&out, []string{"--all"}, backend); err != nil {
		t.Fatalf("KillSessions() 실패: %v", err)
	}

	if len(backend.killedSessionNames) != 0 {
		t.Errorf("종료한 세션 = %q, 다른 워크디렉토리의 세션에는 손대지 않기를 기대", backend.killedSessionNames)
	}
	if !strings.Contains(out.String(), "종료할 세션이 없습니다") {
		t.Errorf("출력 = %q, 종료할 것이 없었다는 안내를 기대", out.String())
	}
}

func TestKillSessionsRejectsAllOptionCombinedWithARepoName(t *testing.T) {
	// --all 과 이름을 함께 받으면 사용자가 무엇을 의도했는지 알 수 없다. 넓은 쪽으로 해석해
	// 전부 죽이면 되돌릴 방법이 없다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()
	backend.listedSessionNames = []string{"kyu-WorkDir-featureX-alpha-commons"}

	var out bytes.Buffer
	if err := KillSessions(&out, []string{"--all", "alpha-commons"}, backend); err == nil {
		t.Fatalf("KillSessions() 가 에러를 반환하지 않음, 인자 에러를 기대")
	}
	if len(backend.killedSessionNames) != 0 {
		t.Errorf("종료한 세션 = %q, 인자가 잘못됐으면 아무것도 죽이지 않기를 기대", backend.killedSessionNames)
	}
}
