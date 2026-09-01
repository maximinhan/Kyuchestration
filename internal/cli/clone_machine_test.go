package cli

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/github"
)

// cloneJSONDocumentForTest 는 kyu clone --json 이 내보내는 문서를 테스트가 직접 다시 적어둔 모양이다.
type cloneJSONDocumentForTest struct {
	SchemaVersion            int                      `json:"schemaVersion"`
	Results                  []cloneJSONResultForTest `json:"results"`
	MainSessionRestartNeeded bool                     `json:"mainSessionRestartNeeded"`
}

type cloneJSONResultForTest struct {
	Repo    string `json:"repo"`
	Status  string `json:"status"`
	Message string `json:"message"`
}

// runMachineCloneForTest 는 묻지 않는 클론을 실행하고 stdout 을 문서로 되읽는다.
//
// 실패로 끝난 실행도 문서를 낸다. 어느 레포가 왜 실패했는지는 그 문서에만 있으므로,
// 종료 코드만 보고 stdout 을 버리면 앱은 사용자에게 아무것도 설명하지 못한다.
func runMachineCloneForTest(t *testing.T, gitHub *fakeGitHub, repoReferences ...string) (cloneJSONDocumentForTest, error) {
	t.Helper()

	args := []string{profileOptionName, "개인"}
	for _, reference := range repoReferences {
		args = append(args, repoOptionName, reference)
	}
	args = append(args, machineJSONOptionName)

	var out bytes.Buffer
	err := CloneRepos(&out, args, gitHub.newAccess, storeWithOneProfile(t))

	var document cloneJSONDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)
	return document, err
}

func TestCloneWithRepoOptionsClonesWithoutAskingAnything(t *testing.T) {
	// 이 PR 의 값어치가 이 한 줄이다 — GUI 는 물음에 답할 수 없으므로, 무엇을 클론할지 미리
	// 적어 보내고 결과만 돌려받아야 앱 안에서 워크디렉토리를 채울 수 있다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(
		makeRepository("proj-a", true, time.Now()),
		makeRepository("proj-b", false, time.Now()),
	)

	document, err := runMachineCloneForTest(t, gitHub, "maximinhan/proj-b")
	if err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	want := []clonedRepository{{name: "proj-b", destinationPath: filepath.Join(workDirPath, "proj-b")}}
	if len(gitHub.clonedRepositories) != 1 || gitHub.clonedRepositories[0] != want[0] {
		t.Errorf("클론 = %+v, want %+v", gitHub.clonedRepositories, want)
	}
	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", document.SchemaVersion)
	}
	if len(document.Results) != 1 || document.Results[0].Repo != "maximinhan/proj-b" || document.Results[0].Status != "cloned" {
		t.Errorf("results = %+v, 적어 보낸 이름 그대로 cloned 를 기대", document.Results)
	}
}

func TestCloneWithJSONReportsEveryRequestedRepositoryInTheOrderItWasAsked(t *testing.T) {
	// 결과를 요청 순서대로 돌려줘야 앱이 자기 화면의 줄과 결과를 이름으로 맞추지 않아도 된다.
	// 세 가지 결말(클론·건너뜀·실패)이 한 번에 섞이는 것이 흔한 경우다.
	workDirPath := makeWorkDir(t)
	if err := os.MkdirAll(filepath.Join(workDirPath, "proj-b"), 0o755); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(
		makeRepository("proj-a", false, time.Now()),
		makeRepository("proj-b", false, time.Now()),
		makeRepository("proj-c", false, time.Now()),
	)
	gitHub.cloneFailureByRepositoryName = map[string]error{"proj-c": errors.New("권한이 없습니다")}

	document, err := runMachineCloneForTest(t, gitHub,
		"maximinhan/proj-a", "maximinhan/proj-b", "maximinhan/proj-c")

	// 하나라도 실패하면 종료 코드로 알린다. 화면의 줄들은 스크롤 위로 사라지지만 종료 코드는 남는다.
	if err == nil {
		t.Fatal("CloneRepos() 가 실패한 레포가 있는데도 성공으로 끝났습니다")
	}

	want := []cloneJSONResultForTest{
		{Repo: "maximinhan/proj-a", Status: "cloned"},
		{Repo: "maximinhan/proj-b", Status: "skipped"},
		{Repo: "maximinhan/proj-c", Status: "failed"},
	}
	if len(document.Results) != len(want) {
		t.Fatalf("results = %+v, want %+v", document.Results, want)
	}
	for resultIndex, wantResult := range want {
		got := document.Results[resultIndex]
		if got.Repo != wantResult.Repo || got.Status != wantResult.Status {
			t.Errorf("results[%d] = %+v, want %+v", resultIndex, got, wantResult)
		}
	}

	// 실패와 건너뜀에는 이유가 붙어야 한다. 상태만으로는 앱이 사용자에게 무엇을 하라고 할지 모른다.
	if !strings.Contains(document.Results[2].Message, "권한이 없습니다") {
		t.Errorf("results[2].message = %q, 실패한 이유를 기대", document.Results[2].Message)
	}
	if document.Results[1].Message == "" {
		t.Error("results[1].message 가 비었습니다 — 왜 건너뛰었는지를 기대")
	}
	if document.Results[0].Message != "" {
		t.Errorf("results[0].message = %q, 클론된 레포에는 빈 문자열을 기대", document.Results[0].Message)
	}

	// 하나가 실패해도 멈추지 않는다. 실패의 흔한 이유(그 레포에 권한이 없다)는 다른 레포와 상관없다.
	if len(gitHub.clonedRepositories) != 1 || gitHub.clonedRepositories[0].name != "proj-a" {
		t.Errorf("클론 = %+v, 실패 뒤에도 남은 레포를 계속 시도하기를 기대", gitHub.clonedRepositories)
	}
}

func TestCloneWithJSONReportsARepositoryTheOwnerDoesNotHave(t *testing.T) {
	// 오타는 흔하다. 목록에 없는 이름을 조용히 건너뛰면 앱은 "클론했다" 고 믿고 다음 걸음으로 간다.
	t.Chdir(makeWorkDir(t))

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))

	document, err := runMachineCloneForTest(t, gitHub, "maximinhan/proj-z")
	if err == nil {
		t.Fatal("CloneRepos() 가 없는 레포를 성공으로 끝냈습니다")
	}

	if len(document.Results) != 1 || document.Results[0].Status != "failed" {
		t.Fatalf("results = %+v, failed 하나를 기대", document.Results)
	}
	if !strings.Contains(document.Results[0].Message, "proj-z") {
		t.Errorf("message = %q, 어느 이름을 찾지 못했는지 알리기를 기대", document.Results[0].Message)
	}
}

func TestCloneAsksTheRepositoryListOncePerOwner(t *testing.T) {
	// 레포마다 목록을 다시 받으면 같은 계정에 대해 같은 왕복을 되풀이하고, GitHub 의 요청 한도가
	// 그만큼 빨리 닳는다. 100 개를 넘는 계정에서는 그 목록 하나가 여러 페이지다.
	t.Chdir(makeWorkDir(t))

	gitHub := newTestGitHubWithRepos(
		makeRepository("proj-a", false, time.Now()),
		makeRepository("proj-b", false, time.Now()),
	)

	if _, err := runMachineCloneForTest(t, gitHub,
		"maximinhan/proj-a", "maximinhan/proj-b"); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.repositoriesAskedFor) != 1 {
		t.Errorf("레포 목록을 물은 횟수 = %d, 소유자마다 한 번을 기대 (%+v)",
			len(gitHub.repositoriesAskedFor), gitHub.repositoriesAskedFor)
	}
}

func TestCloneAlwaysReportsThatNoMainSessionNeedsRestarting(t *testing.T) {
	// 계약이라 필드는 남지만 값은 늘 거짓이다. 엔진에는 떠 있는 세션을 물을 상대가 없다 —
	// 세션은 앱의 PTY 안에 있고 앱만 그것을 안다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))

	document, err := runMachineCloneForTest(t, gitHub, "maximinhan/proj-a")
	if err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if document.MainSessionRestartNeeded {
		t.Error("mainSessionRestartNeeded = true, 엔진이 세션을 보지 못하므로 false 를 기대")
	}
}

func TestCloneWithoutJSONStillClonesWithoutAskingAnything(t *testing.T) {
	// --json 은 출력 형태만 정한다. --repo 를 적은 실행은 사람이 부르든 앱이 부르든 묻지 않는다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	gitHub := newTestGitHubWithRepos(makeRepository("proj-a", false, time.Now()))

	var out bytes.Buffer
	err := CloneRepos(&out,
		[]string{profileOptionName, "개인", repoOptionName, "maximinhan/proj-a"},
		gitHub.newAccess, storeWithOneProfile(t))
	if err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.clonedRepositories) != 1 {
		t.Errorf("클론 = %+v, 하나를 기대", gitHub.clonedRepositories)
	}
	if !strings.Contains(out.String(), "proj-a") {
		t.Errorf("stdout = %q, 무엇을 클론했는지 알리기를 기대", out.String())
	}
}

func TestCloneWithRepoNeedsToKnowWhichTokenToUse(t *testing.T) {
	// 묻지 않는 실행에서 프로필을 고르지 않으면 도구가 대신 고르는 수밖에 없는데, 그것은
	// "머신에 굴러다니는 인증을 집어 쓰지 않는다" 는 이 도구의 원칙과 정면으로 어긋난다.
	t.Chdir(makeWorkDir(t))

	var out bytes.Buffer
	err := CloneRepos(&out,
		[]string{repoOptionName, "maximinhan/proj-a", machineJSONOptionName},
		newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("CloneRepos() 가 프로필 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), profileOptionName) {
		t.Errorf("에러 = %q, 어느 옵션이 필요한지 알리기를 기대", err.Error())
	}
}

func TestCloneRefusesARepoThatIsNotOwnerSlashName(t *testing.T) {
	// 이름만 적으면 어느 계정의 레포인지 알 수 없다. 프로필의 개인 계정으로 대신 정해주면
	// 같은 이름의 조직 레포를 적은 사용자가 엉뚱한 레포를 받는다.
	t.Chdir(makeWorkDir(t))

	var out bytes.Buffer
	err := CloneRepos(&out,
		[]string{profileOptionName, "개인", repoOptionName, "proj-a", machineJSONOptionName},
		newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("CloneRepos() 가 owner 없는 이름을 받아들였습니다")
	}
	if !strings.Contains(err.Error(), "owner/name") {
		t.Errorf("에러 = %q, 어떤 꼴이어야 하는지 알리기를 기대", err.Error())
	}
}

func TestCloneWithoutARepoPointsAtTheCommandThatListsCandidates(t *testing.T) {
	// 무엇을 클론할지 적지 않은 실행을 목록으로 데려가지 않는다. 고를 화면은 앱에 있고,
	// 엔진에는 물어볼 상대가 없다 — 후보를 답하는 자리를 대신 일러준다.
	t.Chdir(makeWorkDir(t))

	var out bytes.Buffer
	err := CloneRepos(&out,
		[]string{profileOptionName, "개인"},
		newTestGitHub().newAccess, storeWithOneProfile(t))
	if err == nil {
		t.Fatal("CloneRepos() 가 --repo 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), repoOptionName) {
		t.Errorf("에러 = %q, 무엇이 빠졌는지 알리기를 기대", err.Error())
	}
	if !strings.Contains(err.Error(), "kyu repos") {
		t.Errorf("에러 = %q, 후보를 답하는 명령을 함께 알리기를 기대", err.Error())
	}
}

func TestCloneAsksTheOrganizationEndpointForARepositoryOfAnOrganization(t *testing.T) {
	// --repo 의 앞자리는 개인 계정일 수도 조직일 수도 있다. 종류를 잘못 잡으면 조직 레포를
	// 개인 경로로 물어 목록에 없다고 답하게 된다.
	t.Chdir(makeWorkDir(t))

	gitHub := newTestGitHub()
	gitHub.repositoriesByOwnerLogin = map[string][]github.Repository{
		"acme": {{Name: "platform", OwnerLogin: "acme", CloneURL: "https://github.com/acme/platform.git"}},
	}

	if _, err := runMachineCloneForTest(t, gitHub, "acme/platform"); err != nil {
		t.Fatalf("CloneRepos() 실패: %v", err)
	}

	if len(gitHub.repositoriesAskedFor) != 1 || !gitHub.repositoriesAskedFor[0].IsOrganization {
		t.Errorf("물은 소유자 = %+v, 토큰의 주인이 아닌 이름은 조직으로 묻기를 기대", gitHub.repositoriesAskedFor)
	}
}
