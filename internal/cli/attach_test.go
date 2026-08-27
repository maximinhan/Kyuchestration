package cli

import (
	"bytes"
	"fmt"
	"slices"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

func TestAttachSessionEntersTheRunningSessionAfterTellingHowToLeave(t *testing.T) {
	// 사용자는 tmux 를 몰라도 된다(설계 문서 8.2). 대신 빠져나오는 방법 하나는 반드시 알아야
	// 하는데, 세션에 들어간 뒤에는 이 도구가 말을 걸 수 없으므로 진입 직전이 유일한 기회다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("wd-WorkDir-featureX-alpha-commons")

	var out bytes.Buffer
	if err := AttachSession(&out, []string{"alpha-commons"}, backend); err != nil {
		t.Fatalf("AttachSession() 실패: %v", err)
	}

	if want := []string{"wd-WorkDir-featureX-alpha-commons"}; !slices.Equal(backend.attachedSessionNames, want) {
		t.Errorf("진입한 세션 = %q, want %q", backend.attachedSessionNames, want)
	}
	if !strings.Contains(out.String(), "Ctrl-b d") {
		t.Errorf("출력 = %q, 빠져나오는 방법 안내를 포함하기를 기대", out.String())
	}
}

func TestAttachSessionAcceptsMainAsTarget(t *testing.T) {
	// 설계 문서 9.3 의 "wd attach <repo> — main 도 가능".
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("wd-WorkDir-featureX-main")

	var out bytes.Buffer
	if err := AttachSession(&out, []string{"main"}, backend); err != nil {
		t.Fatalf("AttachSession() 실패: %v", err)
	}

	if want := []string{"wd-WorkDir-featureX-main"}; !slices.Equal(backend.attachedSessionNames, want) {
		t.Errorf("진입한 세션 = %q, want %q", backend.attachedSessionNames, want)
	}
}

func TestAttachSessionGuidesToStartWhenTheSessionIsNotRunning(t *testing.T) {
	// 없는 세션에 붙으려 하면 백엔드가 알아서 실패하지만, 그 실패는 "무엇을 해야 하는지" 를
	// 알려주지 않는다. 다음 한 걸음이 wd start 라는 것을 여기서 말해준다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend()

	var out bytes.Buffer
	err := AttachSession(&out, []string{"alpha-commons"}, backend)

	if err == nil {
		t.Fatalf("AttachSession() 가 에러를 반환하지 않음, 세션 부재 에러를 기대")
	}
	if !strings.Contains(err.Error(), "wd start alpha-commons") {
		t.Errorf("에러 = %v, 세션을 시작하는 방법 안내를 포함하기를 기대", err)
	}
	if len(backend.attachedSessionNames) != 0 {
		t.Errorf("진입 시도 = %q, 세션이 없으면 붙지 않기를 기대", backend.attachedSessionNames)
	}
}

func TestAttachSessionTranslatesNestedSessionRefusalIntoGuidance(t *testing.T) {
	// 백엔드는 "중첩이다" 까지만 말한다. 그것을 사용자가 할 수 있는 행동으로 옮기는 것이 표시 계층의 몫이다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	backend := newRecordingSessionBackend("wd-WorkDir-featureX-alpha-commons")
	backend.attachError = fmt.Errorf("%w: wd-WorkDir-featureX-alpha-commons", session.ErrNestedSession)

	var out bytes.Buffer
	err := AttachSession(&out, []string{"alpha-commons"}, backend)

	if err == nil {
		t.Fatalf("AttachSession() 가 에러를 반환하지 않음, 중첩 거절을 기대")
	}
	if !strings.Contains(err.Error(), "Ctrl-b d") {
		t.Errorf("에러 = %v, 먼저 빠져나오는 방법 안내를 포함하기를 기대", err)
	}
}

func TestAttachSessionRequiresExactlyOneTarget(t *testing.T) {
	// 어디로 들어갈지는 도구가 골라줄 수 없다. 인자 없이 부른 것을 메인 진입으로 해석하면
	// 사용자가 이름을 빠뜨렸을 때 엉뚱한 세션으로 끌려간다.
	tests := []struct {
		scenario string
		args     []string
	}{
		{scenario: "인자가 없으면 거절한다", args: nil},
		{scenario: "레포를 둘 이상 받으면 거절한다", args: []string{"alpha-commons", "beta-gateway"}},
		{scenario: "옵션처럼 생긴 것은 이름으로 받지 않는다", args: []string{"--없는옵션"}},
	}

	for _, tt := range tests {
		t.Run(tt.scenario, func(t *testing.T) {
			workDirPath := makeWorkDir(t)
			t.Chdir(workDirPath)

			backend := newRecordingSessionBackend()

			var out bytes.Buffer
			if err := AttachSession(&out, tt.args, backend); err == nil {
				t.Fatalf("AttachSession(%q) 가 에러를 반환하지 않음, 인자 개수 에러를 기대", tt.args)
			}
			if len(backend.attachedSessionNames) != 0 {
				t.Errorf("진입 시도 = %q, 인자가 잘못됐으면 붙지 않기를 기대", backend.attachedSessionNames)
			}
		})
	}
}
