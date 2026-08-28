package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

// 아래 타입들은 --json 이 내보내는 문서를 테스트가 직접 다시 적어둔 모양이다.
//
// 생산 코드의 직렬화 타입(listJSONDocument)을 재사용하지 않는다. 그것을 쓰면 필드 이름을 바꿔도
// 테스트가 함께 따라 움직여, 계약이 바뀐 사실이 어디에서도 드러나지 않는다. 읽는 쪽(GUI)은
// 자기 코드에 이 모양을 적어두고 그것에 맞춰 파싱하므로, 테스트도 똑같이 적어두어야
// 이름이나 모양이 바뀌는 순간 여기에서 먼저 깨진다.
type listJSONDocumentForTest struct {
	SchemaVersion int                        `json:"schemaVersion"`
	WorkDir       listJSONWorkDirForTest     `json:"workDir"`
	Repos         []listJSONRepoForTest      `json:"repos"`
	MainSession   listJSONMainSessionForTest `json:"mainSession"`
	PlanWarnings  []string                   `json:"planWarnings"`
}

type listJSONWorkDirForTest struct {
	Name         string `json:"name"`
	AbsolutePath string `json:"absolutePath"`
}

type listJSONRepoForTest struct {
	Name           string               `json:"name"`
	AbsolutePath   string               `json:"absolutePath"`
	State          string               `json:"state"`
	Task           *listJSONTaskForTest `json:"task"`
	DoneTaskCount  int                  `json:"doneTaskCount"`
	TotalTaskCount int                  `json:"totalTaskCount"`
}

type listJSONTaskForTest struct {
	ID        string   `json:"id"`
	Status    string   `json:"status"`
	BlockedBy []string `json:"blockedBy"`
}

type listJSONMainSessionForTest struct {
	Alive bool `json:"alive"`
}

// listJSONRunForTest 는 --json 한 번의 실행 결과다. 되읽은 문서와 두 스트림의 원문을 함께 들고 있다.
//
// 원문까지 남기는 이유는 "무엇이 들었는가" 와 "무엇이 들지 않았는가" 를 둘 다 봐야 해서다.
// 되읽은 문서만으로는 stdout 에 섞인 안내 한 줄도, 빈 배열이 null 로 나간 것도 보이지 않는다.
type listJSONRunForTest struct {
	document listJSONDocumentForTest
	stdout   string
	stderr   string
}

// runListJSONForTest 는 --json 으로 목록을 실행하고 stdout 을 문서로 되읽는다.
//
// 기대 출력을 문자열로 적어두고 통째로 비교하지 않는다. 이 출력의 계약은 "어느 필드에 무엇이
// 들었는가" 이지 들여쓰기나 키 순서가 아니라서, 골든 문자열로 고정하면 계약과 무관한 변경에도
// 테스트가 깨지고 정작 계약이 바뀐 순간은 그 소음에 묻힌다. 읽는 쪽과 같은 방법으로 확인한다.
//
// stdout 에 문서 하나뿐인지도 여기에서 함께 본다. 안내 한 줄이 섞이는 순간 읽는 쪽의 파싱이
// 통째로 깨지므로, JSON 을 보는 모든 테스트가 그 사실을 같이 지켜야 한다.
func runListJSONForTest(t *testing.T, args []string, backend session.SessionBackend) listJSONRunForTest {
	t.Helper()

	var out, errOut bytes.Buffer
	if err := ListWorkDir(&out, &errOut, args, backend); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	stdoutReader := bytes.NewReader(out.Bytes())
	decoder := json.NewDecoder(stdoutReader)

	var document listJSONDocumentForTest
	if err := decoder.Decode(&document); err != nil {
		t.Fatalf("stdout 을 JSON 으로 읽지 못했습니다: %v\n--- stdout ---\n%s", err, out.String())
	}

	// Decoder 가 미리 읽어둔 것과 아직 읽지 않은 것을 모두 합쳐야 "문서 뒤에 아무것도 없다" 를 말할 수 있다.
	trailing, err := io.ReadAll(io.MultiReader(decoder.Buffered(), stdoutReader))
	if err != nil {
		t.Fatalf("stdout 의 남은 바이트를 읽지 못했습니다: %v", err)
	}
	if strings.TrimSpace(string(trailing)) != "" {
		t.Errorf("stdout 의 JSON 뒤에 %q 가 남았습니다 — 문서 하나만 나오기를 기대", trailing)
	}

	return listJSONRunForTest{document: document, stdout: out.String(), stderr: errOut.String()}
}

// repoInDocument 는 문서에서 이름으로 레포 항목 하나를 찾는다.
func repoInDocument(t *testing.T, document listJSONDocumentForTest, repoName string) listJSONRepoForTest {
	t.Helper()

	for _, repo := range document.Repos {
		if repo.Name == repoName {
			return repo
		}
	}
	t.Fatalf("문서에 레포 %q 가 없습니다 (있는 레포: %+v)", repoName, document.Repos)
	return listJSONRepoForTest{}
}

func TestListWorkDirAsJSONDescribesEachRepoWithItsStateAndPlanTask(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeRepoWithUncommittedChange(t, workDirPath, "beta-gateway")
	makeCleanRepo(t, workDirPath, "zeta-service")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: commons-event
    repo: alpha-commons
    title: 이벤트 클래스 추가
    needs: []
    status: done

  - id: publish
    repo: beta-gateway
    title: 발행 로직 구현
    needs: [commons-event]
    status: ready

  - id: consume
    repo: zeta-service
    title: 컨슈머 구현
    needs: [commons-event, publish]
    status: blocked
---

## 배경

본문은 도구가 읽지 않는다.
`)

	backend := newFakeSessionBackend(
		session.RepoSessionName(testWorkDirName, "zeta-service"),
		session.MainSessionName(testWorkDirName),
	)

	run := runListJSONForTest(t, []string{machineJSONOptionName, workDirPath}, backend)

	if run.document.SchemaVersion != listJSONSchemaVersion {
		t.Errorf("schemaVersion = %d, want %d", run.document.SchemaVersion, listJSONSchemaVersion)
	}
	if run.document.WorkDir.Name != testWorkDirName {
		t.Errorf("workDir.name = %q, want %q", run.document.WorkDir.Name, testWorkDirName)
	}
	if run.document.WorkDir.AbsolutePath != workDirPath {
		t.Errorf("workDir.absolutePath = %q, want %q", run.document.WorkDir.AbsolutePath, workDirPath)
	}
	if !run.document.MainSession.Alive {
		t.Errorf("mainSession.alive = false, 메인 세션이 떠 있으므로 true 를 기대")
	}

	// 메인 세션은 레포가 아니다. 사람용 표에서 마지막 행을 차지하는 것과 달리, 여기서는 repos 에
	// 섞이지 않고 자기 자리에만 있어야 읽는 쪽이 레포 수를 세는 데 걸리지 않는다.
	wantRepoNames := []string{"alpha-commons", "beta-gateway", "zeta-service"}
	var gotRepoNames []string
	for _, repo := range run.document.Repos {
		gotRepoNames = append(gotRepoNames, repo.Name)
	}
	if !slices.Equal(gotRepoNames, wantRepoNames) {
		t.Errorf("repos 의 이름 = %q, want %q (이름순, 메인 제외)", gotRepoNames, wantRepoNames)
	}

	alphaRepo := repoInDocument(t, run.document, "alpha-commons")
	if alphaRepo.AbsolutePath != filepath.Join(workDirPath, "alpha-commons") {
		t.Errorf("alpha-commons 의 absolutePath = %q, want %q",
			alphaRepo.AbsolutePath, filepath.Join(workDirPath, "alpha-commons"))
	}
	if alphaRepo.State != "IDLE" {
		t.Errorf("alpha-commons 의 state = %q, want %q", alphaRepo.State, "IDLE")
	}
	// 작업이 하나뿐이고 그것이 끝났으면 사람용 목록은 "[commons-event] done" 을 보여준다.
	// 같은 사실이 여기에도 있어야 두 출력이 같은 것을 말한다.
	if alphaRepo.Task == nil {
		t.Fatalf("alpha-commons 의 task = null, 끝난 작업 하나를 기대")
	}
	if alphaRepo.Task.ID != "commons-event" || alphaRepo.Task.Status != "done" {
		t.Errorf("alpha-commons 의 task = %+v, id=commons-event status=done 을 기대", *alphaRepo.Task)
	}
	if alphaRepo.DoneTaskCount != 1 || alphaRepo.TotalTaskCount != 1 {
		t.Errorf("alpha-commons 의 진척 = %d/%d, want 1/1", alphaRepo.DoneTaskCount, alphaRepo.TotalTaskCount)
	}

	betaRepo := repoInDocument(t, run.document, "beta-gateway")
	if betaRepo.State != "DIRTY" {
		t.Errorf("beta-gateway 의 state = %q, want %q", betaRepo.State, "DIRTY")
	}
	if betaRepo.Task == nil {
		t.Fatalf("beta-gateway 의 task = null, ready 작업 하나를 기대")
	}
	if betaRepo.Task.ID != "publish" || betaRepo.Task.Status != "ready" {
		t.Errorf("beta-gateway 의 task = %+v, id=publish status=ready 를 기대", *betaRepo.Task)
	}
	// 막히지 않은 작업에는 기다리는 것이 없다. null 이 아니라 빈 배열이어야 읽는 쪽이 그냥 순회한다.
	if betaRepo.Task.BlockedBy == nil || len(betaRepo.Task.BlockedBy) != 0 {
		t.Errorf("beta-gateway 의 task.blockedBy = %#v, 빈 배열을 기대", betaRepo.Task.BlockedBy)
	}

	zetaRepo := repoInDocument(t, run.document, "zeta-service")
	if zetaRepo.State != "RUNNING" {
		t.Errorf("zeta-service 의 state = %q, want %q", zetaRepo.State, "RUNNING")
	}
	if zetaRepo.Task == nil {
		t.Fatalf("zeta-service 의 task = null, blocked 작업 하나를 기대")
	}
	if zetaRepo.Task.Status != "blocked" {
		t.Errorf("zeta-service 의 task.status = %q, want %q", zetaRepo.Task.Status, "blocked")
	}
	// 끝난 선행(commons-event)은 빠지고 남은 것만 온다 — 사람용 목록의 "← needs publish" 와 같은 사실이다.
	if !slices.Equal(zetaRepo.Task.BlockedBy, []string{"publish"}) {
		t.Errorf("zeta-service 의 task.blockedBy = %q, want %q", zetaRepo.Task.BlockedBy, []string{"publish"})
	}

	if run.stderr != "" {
		t.Errorf("stderr = %q, JSON 모드에서는 비어 있기를 기대", run.stderr)
	}
}

func TestListWorkDirAsJSONShowsTheSameTaskThePeopleListShows(t *testing.T) {
	// 사람용 목록은 한 레포에 작업이 여럿이면 doing → ready → blocked 순으로 하나만 고른다.
	// JSON 이 다른 규칙으로 고르면 GUI 와 터미널이 같은 워크디렉토리를 두고 다른 말을 하게 된다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: first-done
    repo: alpha-commons
    title: 끝난 작업
    status: done
  - id: waiting
    repo: alpha-commons
    title: 막힌 작업
    needs: [first-done]
    status: blocked
  - id: startable
    repo: alpha-commons
    title: 시작 가능한 작업
    status: ready
  - id: in-progress
    repo: alpha-commons
    title: 진행 중인 작업
    status: doing
---
`)

	run := runListJSONForTest(t, []string{machineJSONOptionName, workDirPath}, newFakeSessionBackend())

	repo := repoInDocument(t, run.document, "alpha-commons")
	if repo.Task == nil {
		t.Fatalf("task = null, 진행 중인 작업을 기대")
	}
	if repo.Task.ID != "in-progress" {
		t.Errorf("task.id = %q, want %q (사람용 목록이 고르는 것과 같아야 한다)", repo.Task.ID, "in-progress")
	}
	// 사람용 목록의 "(done 1/4)" 와 같은 사실이다. 보여준 작업 말고도 있다는 것을 이 숫자가 알린다.
	if repo.DoneTaskCount != 1 || repo.TotalTaskCount != 4 {
		t.Errorf("진척 = %d/%d, want 1/4", repo.DoneTaskCount, repo.TotalTaskCount)
	}
}

func TestListWorkDirAsJSONLeavesTheTaskNullWhenThePlanHasNothingForTheRepo(t *testing.T) {
	// 계획 파일이 아예 없는 워크디렉토리다. 사람용 목록에서 계획 칸이 비는 것과 같은 자리다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	run := runListJSONForTest(t, []string{machineJSONOptionName, workDirPath}, newFakeSessionBackend())

	repo := repoInDocument(t, run.document, "alpha-commons")
	if repo.Task != nil {
		t.Errorf("task = %+v, 계획이 없으므로 null 을 기대", *repo.Task)
	}
	if repo.DoneTaskCount != 0 || repo.TotalTaskCount != 0 {
		t.Errorf("진척 = %d/%d, want 0/0", repo.DoneTaskCount, repo.TotalTaskCount)
	}
	// 경고가 없을 때 null 로 나가면 읽는 쪽이 "없음" 을 두 가지 모양으로 다뤄야 한다.
	if run.document.PlanWarnings == nil {
		t.Errorf("planWarnings = null, 빈 배열을 기대")
	}
}

func TestListWorkDirAsJSONCarriesPlanWarningsAsAFieldInsteadOfStderr(t *testing.T) {
	// 사람용 모드에서 stderr 로 나가던 경고다. 읽는 쪽은 스트림 하나만 보므로 문서 안으로 들어가야 하고,
	// 그러지 않으면 "계획을 버렸다" 는 사실이 GUI 에서 통째로 사라진다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: here
    repo: alpha-commons
    title: 있는 레포의 작업
    status: ready
  - id: elsewhere
    repo: not-cloned-yet
    title: 없는 레포의 작업
    status: ready
---
`)

	run := runListJSONForTest(t, []string{machineJSONOptionName, workDirPath}, newFakeSessionBackend())

	if len(run.document.PlanWarnings) != 1 {
		t.Fatalf("planWarnings = %q, 없는 레포에 대한 경고 한 줄을 기대", run.document.PlanWarnings)
	}
	if !strings.Contains(run.document.PlanWarnings[0], "not-cloned-yet") {
		t.Errorf("planWarnings[0] = %q, 없는 레포 이름을 포함하기를 기대", run.document.PlanWarnings[0])
	}
	if !strings.Contains(run.document.PlanWarnings[0], filepath.Join(".coord", "plan.md")) {
		t.Errorf("planWarnings[0] = %q, 고칠 파일의 경로를 포함하기를 기대", run.document.PlanWarnings[0])
	}
	if run.stderr != "" {
		t.Errorf("stderr = %q, 경고까지 문서로 들어가므로 비어 있기를 기대", run.stderr)
	}

	// 계획에 문제가 있어도 레포 목록 자체는 그대로 나온다(설계 문서 6.1).
	repo := repoInDocument(t, run.document, "alpha-commons")
	if repo.Task == nil || repo.Task.ID != "here" {
		t.Errorf("alpha-commons 의 task = %+v, id=here 를 기대", repo.Task)
	}
}

func TestListWorkDirAsJSONWritesAnEmptyArrayWhenThereIsNoRepo(t *testing.T) {
	workDirPath := makeWorkDir(t)

	run := runListJSONForTest(t, []string{machineJSONOptionName, workDirPath}, newFakeSessionBackend())

	if len(run.document.Repos) != 0 {
		t.Errorf("repos = %+v, 빈 목록을 기대", run.document.Repos)
	}
	if run.document.MainSession.Alive {
		t.Errorf("mainSession.alive = true, 세션이 없으므로 false 를 기대")
	}

	// 되읽은 값으로는 null 과 [] 가 구분되지 않으므로 원문에서 본다.
	var rawFields map[string]json.RawMessage
	if err := json.Unmarshal([]byte(run.stdout), &rawFields); err != nil {
		t.Fatalf("stdout 을 필드별 원문으로 읽지 못했습니다: %v", err)
	}
	if got := string(rawFields["repos"]); got != "[]" {
		t.Errorf("repos 의 원문 = %s, want []", got)
	}
	if got := string(rawFields["planWarnings"]); got != "[]" {
		t.Errorf("planWarnings 의 원문 = %s, want []", got)
	}
}

func TestListWorkDirAsJSONKeepsHumanOnlyTextOutOfStdout(t *testing.T) {
	// 레포가 없으면 사람용 목록은 안내 문구를 내고, 계획이 깨졌으면 경고를 낸다.
	// JSON 모드의 stdout 에는 둘 다 없어야 한다 — 읽는 쪽은 파싱만 하고, 문장 한 줄이 섞이면 통째로 깨진다.
	workDirPath := makeWorkDir(t)

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: broken
    repo: alpha-commons
    title: 상태가 빠진 작업
---
`)

	run := runListJSONForTest(t, []string{machineJSONOptionName, workDirPath}, newFakeSessionBackend())

	for _, humanOnlyText := range []string{emptyWorkDirGuidance, attachGuidance, planWarningPrefix, "WorkDir:"} {
		if strings.Contains(run.stdout, humanOnlyText) {
			t.Errorf("stdout 에 사람용 문구 %q 가 있습니다\n--- stdout ---\n%s", humanOnlyText, run.stdout)
		}
	}
	if run.stderr != "" {
		t.Errorf("stderr = %q, JSON 모드에서는 비어 있기를 기대", run.stderr)
	}
	if len(run.document.PlanWarnings) == 0 {
		t.Errorf("planWarnings = %q, 깨진 계획의 이유가 문서에 들어오기를 기대", run.document.PlanWarnings)
	}
}

func TestListWorkDirTakesTheJSONOptionBeforeOrAfterThePath(t *testing.T) {
	// 옵션과 경로의 순서는 사용자가 정한다. 한쪽만 되면 나머지 한쪽은 "경로 두 개" 로 거절당한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	afterPath := runListJSONForTest(t, []string{workDirPath, machineJSONOptionName}, newFakeSessionBackend())
	beforePath := runListJSONForTest(t, []string{machineJSONOptionName, workDirPath}, newFakeSessionBackend())

	if afterPath.stdout != beforePath.stdout {
		t.Errorf("옵션 위치에 따라 출력이 다릅니다.\n--- 경로 뒤 ---\n%s\n--- 경로 앞 ---\n%s",
			afterPath.stdout, beforePath.stdout)
	}
}

func TestListWorkDirRejectsAnUnknownOption(t *testing.T) {
	// 모르는 옵션을 경로로 흘려보내면 "워크디렉토리 읽기 실패 (--jsonn)" 이라는 엉뚱한 안내로 끝난다.
	var out, errOut bytes.Buffer
	err := ListWorkDir(&out, &errOut, []string{"--jsonn"}, newFakeSessionBackend())

	if err == nil {
		t.Fatalf("ListWorkDir() 가 에러를 반환하지 않음, 알 수 없는 옵션 에러를 기대")
	}
	if !strings.Contains(err.Error(), "--jsonn") {
		t.Errorf("ListWorkDir() 에러 = %v, 받은 옵션을 그대로 포함하기를 기대", err)
	}
	if out.Len() != 0 {
		t.Errorf("ListWorkDir() 가 %q 를 출력, 인자가 잘못됐으면 아무것도 쓰지 않기를 기대", out.String())
	}
}
