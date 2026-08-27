package session

import (
	"strings"
	"testing"
)

func TestRepoSessionName(t *testing.T) {
	tests := []struct {
		scenario string
		workdir  string
		repo     string
		want     string
	}{
		{
			scenario: "평범한 이름은 kyu-<workdir>-<repo> 로 조립된다",
			workdir:  "WorkDir-featureX",
			repo:     "proj-a",
			want:     "kyu-WorkDir-featureX-proj-a",
		},
		{
			scenario: "레포 이름의 점은 pane 지정자로 해석되므로 하이픈으로 치환한다",
			workdir:  "featureX",
			repo:     "proj.a",
			want:     "kyu-featureX-proj-a",
		},
		{
			scenario: "레포 이름의 콜론은 window 지정자로 해석되므로 하이픈으로 치환한다",
			workdir:  "featureX",
			repo:     "proj:a",
			want:     "kyu-featureX-proj-a",
		},
		{
			scenario: "워크디렉토리 이름에 있는 점과 콜론도 똑같이 치환한다",
			workdir:  "v1.2:rc",
			repo:     "proj-a",
			want:     "kyu-v1-2-rc-proj-a",
		},
		{
			scenario: "치환 대상이 연달아 나와도 각각 하이픈이 된다",
			workdir:  "featureX",
			repo:     "a.:b",
			want:     "kyu-featureX-a--b",
		},
	}

	for _, tt := range tests {
		t.Run(tt.scenario, func(t *testing.T) {
			got := RepoSessionName(tt.workdir, tt.repo)
			if got != tt.want {
				t.Errorf("RepoSessionName(%q, %q) = %q, want %q", tt.workdir, tt.repo, got, tt.want)
			}
		})
	}
}

func TestMainSessionName(t *testing.T) {
	tests := []struct {
		scenario string
		workdir  string
		want     string
	}{
		{
			scenario: "메인 세션은 레포 자리에 main 이 들어간다",
			workdir:  "WorkDir-featureX",
			want:     "kyu-WorkDir-featureX-main",
		},
		{
			scenario: "워크디렉토리 이름의 점과 콜론은 하이픈으로 치환한다",
			workdir:  "v1.2:rc",
			want:     "kyu-v1-2-rc-main",
		},
	}

	for _, tt := range tests {
		t.Run(tt.scenario, func(t *testing.T) {
			got := MainSessionName(tt.workdir)
			if got != tt.want {
				t.Errorf("MainSessionName(%q) = %q, want %q", tt.workdir, got, tt.want)
			}
		})
	}
}

func TestWorkDirSessionPrefix(t *testing.T) {
	tests := []struct {
		scenario string
		workdir  string
		want     string
	}{
		{
			scenario: "한 워크디렉토리의 세션은 kyu-<workdir>- 를 공유한다",
			workdir:  "WorkDir-featureX",
			want:     "kyu-WorkDir-featureX-",
		},
		{
			scenario: "이름 규칙과 같은 치환을 거친다 — 그러지 않으면 실제 세션 이름과 어긋나 아무것도 걸러내지 못한다",
			workdir:  "v1.2:rc",
			want:     "kyu-v1-2-rc-",
		},
	}

	for _, tt := range tests {
		t.Run(tt.scenario, func(t *testing.T) {
			got := WorkDirSessionPrefix(tt.workdir)
			if got != tt.want {
				t.Errorf("WorkDirSessionPrefix(%q) = %q, want %q", tt.workdir, got, tt.want)
			}

			// 접두사가 실제 세션 이름의 앞부분과 같아야 필터가 성립한다. 두 함수가 따로 놀면
			// 한쪽만 고쳤을 때 --all 이 조용히 아무것도 죽이지 않는다.
			if repoSessionName := RepoSessionName(tt.workdir, "proj-a"); !strings.HasPrefix(repoSessionName, got) {
				t.Errorf("RepoSessionName(%q, \"proj-a\") = %q, 접두사 %q 로 시작하기를 기대", tt.workdir, repoSessionName, got)
			}
		})
	}
}
