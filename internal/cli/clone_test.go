package cli

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// newTestGitHubWithRepos 는 프로필 하나로 곧장 붙을 수 있는 GitHub 대역을 만든다.
func newTestGitHubWithRepos(repositories ...github.Repository) *fakeGitHub {
	gitHub := newTestGitHub()
	gitHub.repositoriesByOwnerLogin = map[string][]github.Repository{"maximinhan": repositories}
	return gitHub
}

// storeWithOneProfile 은 프로필이 하나 등록된 저장소다. 대화가 "1" 한 줄로 시작하게 한다.
func storeWithOneProfile(t *testing.T) *fakeTokenStore {
	t.Helper()

	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("개인", validTestToken); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	return tokenStore
}

func TestCloneClonesEveryRepositoryTheUserSelectedIntoTheWorkDir(t *testing.T) {
	// 이 명령의 값어치가 이 한 줄이다 — 목록에서 번호만 고르면 워크디렉토리에 레포가 놓인다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	pushedAt := time.Date(2026, 8, 27, 0, 0, 0, 0, time.UTC)
	gitHub := newTestGitHubWithRepos(
		makeRepository("proj-a", true, pushedAt),
		makeRepository("proj-b", false, pushedAt),
		makeRepository("proj-c", false, pushedAt),
	)
	backend := newRecordingSessionBackend()

	// 프로필 선택 → 레포 선택. 소유자는 조직이 없으므로 묻지 않는다.
	input := strings.NewReader("1\n1,3\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), backend); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.clonedRepositories) != 2 {
		t.Fatalf("클론 = %+v, 고른 두 개를 기대", gitHub.clonedRepositories)
	}
	want := []clonedRepository{
		{name: "proj-a", destinationPath: filepath.Join(workDirPath, "proj-a")},
		{name: "proj-c", destinationPath: filepath.Join(workDirPath, "proj-c")},
	}
	for cloneIndex, wantClone := range want {
		if gitHub.clonedRepositories[cloneIndex] != wantClone {
			t.Errorf("클론 %d = %+v, want %+v", cloneIndex, gitHub.clonedRepositories[cloneIndex], wantClone)
		}
	}
}

func TestCloneMarksAndSkipsRepositoriesThatAreAlreadyInTheWorkDir(t *testing.T) {
	// 같은 이름의 디렉토리가 이미 있으면 git clone 은 실패한다. 그 실패를 사용자에게 넘기지 않고
	// 목록에서 미리 알려주고 건너뛴다 — 워크디렉토리에 이미 클론해둔 레포가 섞여 있는 것이 흔하다.
	workDirPath := makeWorkDir(t)
	if err := os.MkdirAll(filepath.Join(workDirPath, "proj-a"), 0o755); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(
		makeRepository("proj-a", false, time.Now()),
		makeRepository("proj-b", false, time.Now()),
	)

	input := strings.NewReader("1\n1,2\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.clonedRepositories) != 1 || gitHub.clonedRepositories[0].name != "proj-b" {
		t.Errorf("클론 = %+v, 이미 있는 레포는 건너뛰기를 기대", gitHub.clonedRepositories)
	}
	if !strings.Contains(out.String(), "이미 있음") {
		t.Errorf("출력 = %q, 목록에 이미 있음 표시를 기대", out.String())
	}
}

func TestCloneShowsWhichRepositoriesArePrivateAndWhenTheyWerePushed(t *testing.T) {
	// 이름만 나열하면 사용자는 목록에서 자기가 찾는 레포를 고르지 못한다. private 표시는
	// 화면을 공유한 채 목록을 여는 상황에서도 의미가 있다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	// 푸시 시각을 실행 머신의 시간대로 만든다. UTC 로 적으면 목록이 로컬 날짜를 찍는 한
	// 시간대가 다른 머신에서 하루 어긋난 날짜를 기대하게 되어, CI 에서만 깨지는 테스트가 된다.
	gitHub := newTestGitHubWithRepos(
		makeRepository("비공개-레포", true, time.Date(2026, 8, 27, 12, 0, 0, 0, time.Local)),
		makeRepository("공개-레포", false, time.Time{}),
	)

	input := strings.NewReader("1\n\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	listing := out.String()
	if !strings.Contains(listing, "private") {
		t.Errorf("출력 = %q, private 표시를 기대", listing)
	}
	if !strings.Contains(listing, "2026-08-27") {
		t.Errorf("출력 = %q, 마지막 푸시 날짜를 기대", listing)
	}
}

func TestCloneWithAnEmptySelectionCancelsWithoutCloningAnything(t *testing.T) {
	// 목록을 보고 그만두는 것은 실패가 아니다. 종료 코드 1 로 끝나면 스크립트가 그것을 실패로 읽는다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))

	input := strings.NewReader("1\n\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 가 취소를 실패로 끝냈습니다: %v", err)
	}

	if len(gitHub.clonedRepositories) != 0 {
		t.Errorf("클론 = %+v, 아무것도 클론하지 않기를 기대", gitHub.clonedRepositories)
	}
	if !strings.Contains(out.String(), "취소") {
		t.Errorf("출력 = %q, 취소했다는 안내를 기대", out.String())
	}
}

func TestCloneAsksWhichOwnerWhenTheAccountBelongsToOrganizations(t *testing.T) {
	// 조직 레포를 클론하려면 소유자를 먼저 골라야 한다 — 목록을 묻는 API 경로가 다르다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHub()
	gitHub.organizations = []github.Owner{{Login: "some-org", IsOrganization: true}}
	gitHub.repositoriesByOwnerLogin = map[string][]github.Repository{
		"maximinhan": {makeRepository("개인-레포", false, time.Now())},
		"some-org":   {{Name: "조직-레포", OwnerLogin: "some-org", CloneURL: "https://github.com/some-org/조직-레포.git"}},
	}

	// 프로필 → 소유자(2 번 = 조직) → 레포 1 번.
	input := strings.NewReader("1\n2\n1\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.clonedRepositories) != 1 || gitHub.clonedRepositories[0].name != "조직-레포" {
		t.Errorf("클론 = %+v, 조직 레포를 기대", gitHub.clonedRepositories)
	}
}

func TestCloneDoesNotAskWhichOwnerWhenThereIsNoOrganization(t *testing.T) {
	// 고를 것이 하나뿐인 물음은 묻지 않는다. 물으면 사용자는 매번 1 을 치게 된다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))

	// 소유자를 묻는다면 이 입력의 두 번째 줄("1")이 소유자 답으로 쓰이고 레포 선택은 입력이 없어 끝난다.
	input := strings.NewReader("1\n1\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.clonedRepositories) != 1 {
		t.Errorf("클론 = %+v, 소유자를 묻지 않고 레포 선택으로 가기를 기대", gitHub.clonedRepositories)
	}
}

func TestCloneKeepsGoingAfterOneFailureAndReportsWhichOnesFailed(t *testing.T) {
	// 하나가 실패했다고 나머지를 포기하면 사용자는 남은 레포를 손으로 클론해야 한다.
	// 대신 무엇이 실패했는지는 마지막에 한 번 더 모아서 말한다 — 스크롤 위로 사라지기 때문이다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(
		makeRepository("proj-a", false, time.Now()),
		makeRepository("proj-b", false, time.Now()),
	)
	gitHub.cloneFailureByRepositoryName = map[string]error{"proj-a": errors.New("접근 권한 없음")}

	input := strings.NewReader("1\n1,2\n")
	var out, errOut bytes.Buffer

	err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend())
	if err == nil {
		t.Fatal("CloneRepos() 가 실패를 알리지 않고 끝났습니다")
	}
	if !strings.Contains(err.Error(), "proj-a") {
		t.Errorf("에러 = %q, 실패한 레포 이름을 기대", err.Error())
	}

	if len(gitHub.clonedRepositories) != 1 || gitHub.clonedRepositories[0].name != "proj-b" {
		t.Errorf("클론 = %+v, 실패 뒤에도 나머지를 계속하기를 기대", gitHub.clonedRepositories)
	}
}

func TestCloneWarnsThatNewReposDoNotReachTheRunningMainSession(t *testing.T) {
	// 세션의 --add-dir 목록은 세션을 만드는 순간 굳는다(PR 10·12 와 같은 자리). 이것을 알리지
	// 않으면 사용자는 방금 클론한 레포가 세션에서 왜 안 보이는지 알 수 없다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))
	backend := newRecordingSessionBackend("kyu-" + testWorkDirName + "-main")

	input := strings.NewReader("1\n1\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), backend); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if !strings.Contains(errOut.String(), "kyu kill main") {
		t.Errorf("errOut = %q, 세션을 다시 띄우는 방법 안내를 기대", errOut.String())
	}
}

func TestCloneStaysQuietAboutTheMainSessionWhenNoneIsRunning(t *testing.T) {
	// 떠 있지도 않은 세션을 다시 띄우라는 안내는 사용자를 헷갈리게 한다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))

	input := strings.NewReader("1\n1\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if strings.Contains(errOut.String(), "kyu kill main") {
		t.Errorf("errOut = %q, 세션이 없으면 재시작 안내를 하지 않기를 기대", errOut.String())
	}
}

func TestCloneRefusesArgumentsWithItsOwnUsage(t *testing.T) {
	// 이 명령은 인자를 받지 않는다. 조용히 무시하면 사용자는 자기가 적은 것이 반영됐다고 믿는다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	var out, errOut bytes.Buffer
	err := CloneRepos(strings.NewReader(""), &out, &errOut, []string{"maximinhan"},
		newTestGitHub().newAccess, storeWithOneProfile(t), newRecordingSessionBackend())

	if err == nil {
		t.Fatal("CloneRepos() 가 인자를 그대로 받았습니다")
	}
	if !strings.Contains(err.Error(), "사용법: kyu clone") {
		t.Errorf("에러 = %q, clone 의 사용법 안내를 기대", err.Error())
	}
}

func TestCloneContinuesWithThePersonalAccountWhenTheTokenCannotListOrganizations(t *testing.T) {
	// fine-grained 토큰은 조직 목록에 403 으로 답한다. 그것으로 명령 전체를 끝내면 개인 레포를
	// 클론하려던 사용자가 아무것도 하지 못한다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))
	gitHub.organizationsError = fmt.Errorf("조직 목록 조회 실패: %w", github.ErrForbidden)

	input := strings.NewReader("1\n1\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.clonedRepositories) != 1 {
		t.Errorf("클론 = %+v, 개인 계정으로 계속하기를 기대", gitHub.clonedRepositories)
	}
	if !strings.Contains(errOut.String(), "조직") {
		t.Errorf("errOut = %q, 조직 목록을 보지 못했다는 안내를 기대", errOut.String())
	}
}

func TestCloneWithoutAnyRepositoryToShowSaysSoInsteadOfAskingForANumber(t *testing.T) {
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos()

	input := strings.NewReader("1\n")
	var out, errOut bytes.Buffer

	if err := CloneRepos(input, &out, &errOut, nil, gitHub.newAccess, storeWithOneProfile(t), newRecordingSessionBackend()); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if !strings.Contains(out.String(), "레포가 없습니다") {
		t.Errorf("출력 = %q, 보여줄 레포가 없다는 안내를 기대", out.String())
	}
}
