// Package cli 는 명령 하나하나의 동작을 담는 표시 계층이다.
//
// 설계 문서 5.1 의 3층 구조에서 가장 위이고, 교체 가능한 껍데기다 — v2 의 TUI 가 이 자리를 대신한다.
// 그래서 이 패키지는 판정 로직을 갖지 않는다. 조율 계층(internal/workdir)에 묻고,
// 그 답을 사람이 읽을 형태로 옮기는 것까지가 이 패키지의 몫이다.
package cli

import (
	"bytes"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"text/tabwriter"

	"github.com/maximinhan/Kyuchestration/internal/session"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// mainRowLabel 은 메인 세션 행에 찍히는 이름이다.
//
// 세션 이름 규칙에서 레포 자리에 들어가는 main 과 같은 낱말이지만, 이쪽은 화면에 보이는 표시다.
// 사용자가 `kyu attach main` 이라고 부를 때의 이름이 세션 이름 규칙과 함께 움직여야 할 이유가 없어
// session 패키지의 상수를 끌어오지 않는다.
const mainRowLabel = "main"

// ListWorkDir 는 kyu list 를 실행한다. 워크디렉토리의 레포 목록과 각 레포의 상태를 out 에 쓴다.
//
// --json 이면 사람이 읽는 표 대신 기계가 읽는 문서 하나를 낸다(list_json.go). 관찰은 두 모드가
// 똑같이 하고 갈리는 것은 옮기는 형태뿐이라, 이 함수에서 한 번만 갈라진다.
//
// 계획에 대한 경고는 out 이 아니라 errOut 으로 나간다. 목록은 다른 명령의 입력으로 넘길 수 있어야
// 하는데 경고가 섞이면 그 쓰임이 깨지고, 계획이 깨져도 목록 자체는 그대로 성립하기 때문이다
// (설계 문서 6.1 의 "계획 파싱 실패가 도구 전체를 막지 않는다").
//
// backend 는 SessionBackend 인터페이스로만 받는다. 어느 백엔드를 쓸지는 진입점(cmd/kyu)의 결정이고,
// 표시 계층이 tmux 를 직접 만들면 백엔드가 늘어날 때마다 이 파일이 함께 바뀐다.
func ListWorkDir(out, errOut io.Writer, args []string, backend session.SessionBackend) error {
	request, err := parseListArgs(args)
	if err != nil {
		return err
	}

	listing, err := inspectWorkDir(request.workDirPath, backend)
	if err != nil {
		return err
	}

	// JSON 모드에서는 경고도 문서 안으로 들어간다. 읽는 쪽은 스트림 하나만 파싱하는데,
	// 사람에게 하는 말이 그 스트림에 섞이면 파싱이 깨지고 다른 스트림으로 빼면 그냥 사라진다.
	if request.asJSON {
		return writeWorkDirListingAsJSON(out, listing)
	}

	// 경고를 먼저 내보낸다. 목록이 길면 사용자의 화면에는 마지막 몇 줄만 남는데,
	// 경고가 그 뒤에 붙으면 "계획을 버렸다" 는 사실이 목록보다 눈에 먼저 들어와 순서가 뒤집힌다.
	if err := writePlanWarnings(errOut, listing.planWarnings); err != nil {
		return err
	}

	return writeWorkDirListing(out, listing)
}

// planWarningPrefix 는 경고 줄 앞에 붙는 말이다. stderr 를 터미널에서 같은 화면으로 보게 되므로
// 목록의 한 줄과 섞이지 않도록 표시한다.
const planWarningPrefix = "경고: "

// writePlanWarnings 는 계획에 대한 경고를 사용자에게 내보낸다.
func writePlanWarnings(errOut io.Writer, warnings []string) error {
	for _, warning := range warnings {
		if _, err := fmt.Fprintf(errOut, "%s%s\n", planWarningPrefix, warning); err != nil {
			return fmt.Errorf("계획 경고 출력 실패: %w", err)
		}
	}
	return nil
}

const listUsageText = `사용법: kyu list [path] [옵션]

  kyu list           현재 디렉토리의 레포 목록과 상태
  kyu list <path>    그 워크디렉토리의 레포 목록과 상태

옵션:
  --json             사람용 표 대신 기계용 JSON 을 낸다 (GUI·스크립트 연동용)`

// listRequest 는 파싱이 끝난 kyu list 요청이다.
type listRequest struct {
	// workDirPath 는 목록을 보여줄 워크디렉토리다. 인자가 없으면 현재 디렉토리다.
	workDirPath string

	// asJSON 은 사람용 표 대신 기계용 JSON 을 낼지다.
	asJSON bool
}

// parseListArgs 는 인자를 목록 요청으로 옮긴다.
//
// 경로를 두 개 이상 받으면 거절한다. 하나를 골라 나머지를 조용히 버리면 사용자는 자기가 지정한
// 워크디렉토리를 보고 있다고 착각한다.
func parseListArgs(args []string) (listRequest, error) {
	request := listRequest{workDirPath: "."}
	pathGiven := false

	for _, arg := range args {
		switch {
		case arg == machineJSONOptionName:
			request.asJSON = true

		// 모르는 옵션을 경로로 흘려보내면 "워크디렉토리 읽기 실패 (--jsonn)" 이라는 엉뚱한 안내가 나온다.
		case strings.HasPrefix(arg, "-"):
			return listRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, listUsageText)

		case pathGiven:
			return listRequest{}, fmt.Errorf("list 는 워크디렉토리 경로 하나만 받습니다 (인자 %d 개를 받음)\n\n%s", len(args), listUsageText)

		default:
			request.workDirPath, pathGiven = arg, true
		}
	}

	return request, nil
}

// listRow 는 사람이 읽는 표의 한 줄이다. 레포 하나 또는 메인 세션에 대응한다.
//
// 관찰 결과가 아니라 그것을 표로 옮긴 결과다. 같은 관찰에서 --json 은 전혀 다른 모양을 만들어내므로,
// 표에만 있는 것 — 메인 세션을 레포와 같은 어휘로 부르는 것, 계획을 한 줄 문구로 접는 것 —
// 은 이 타입 안에서 끝나야 한다.
type listRow struct {
	// label 은 화면에 찍히고 사용자가 명령 인자로 되돌려 주는 이름이다.
	label string

	// state 는 관찰로 얻은 상태다. 메인 행도 같은 어휘를 쓰지만 RUNNING 아니면 IDLE 뿐이다
	// — 워크디렉토리 최상위는 레포가 아니라 DIRTY·AHEAD 라는 말이 성립하지 않는다.
	state workdir.RepoState

	// planNote 는 계획에서 이 레포를 보고 뽑은 한 줄이다(설계 문서 9.3 의 "[commons-event] done").
	// 계획이 없거나 이 레포를 가리키는 작업이 없으면 빈 문자열이고, 그러면 그 칸은 비어 있다.
	planNote string
}

// observedRepo 는 레포 하나에 대해 관찰과 계획 조회가 모두 끝난 결과다.
//
// 표시 문구를 담지 않는다. 이 값 하나에서 사람용 표의 한 줄도, JSON 의 레포 항목도 나오는데,
// 관찰 단계에서 문자열로 굳혀버리면 뒤에 오는 출력은 그 문자열을 되파싱하는 수밖에 없다.
type observedRepo struct {
	// name 은 워크디렉토리 바로 아래의 디렉토리명이다. 사용자가 명령 인자로 되돌려 주는 이름이다.
	name string

	// absolutePath 는 레포의 절대경로다. 이름은 레포를 특정하지 못하므로(설계 문서 11절 4번),
	// 이 도구 밖에서 그 레포를 여는 쪽 — 이를테면 GUI — 은 이름이 아니라 이 값을 봐야 한다.
	absolutePath string

	// state 는 도구가 세션과 git 을 관찰해 판정한 상태다.
	state workdir.RepoState

	// planSummary 는 계획에서 이 레포를 보고 뽑은 것이다. 계획이 없으면 영 값이다.
	planSummary repoPlanSummary

	// hasRecordedConversation 은 이 레포의 세션이 이어갈 대화가 적혀 있는지다.
	//
	// state 와 다른 축의 사실이다 — state 는 지금 세션이 돌고 있는가이고, 이것은 다음에 열면
	// 이어갈 것이 있는가다. 앱이 워크디렉토리를 연 자리에서 카드에 표시를 내는 근거이고
	// (설계 문서 5.5.3), 앱은 그것을 알려고 session-command 를 미리 부를 수 없다 —
	// 묻는 것이 곧 기록하는 것이라 화면을 그리려던 물음이 대화를 만들어 버린다.
	hasRecordedConversation bool
}

// workDirListing 은 출력에 옮기기 직전의 관찰 결과다.
//
// 관찰과 출력을 이 타입에서 끊는다. 출력 형식이 늘어도(사람용 표, --json) 판정 코드는 그대로이고,
// v2 에서 TUI 가 표시 계층을 대신할 때 가져갈 것이 무엇인지도 이 타입이 알려준다.
type workDirListing struct {
	workDirName string

	// workDirAbsolutePath 는 관찰한 워크디렉토리의 절대경로다. 인자로 받은 "." 을 편 결과다.
	workDirAbsolutePath string

	repos []observedRepo

	// mainSessionAlive 는 메인 세션이 떠 있는지다. 레포처럼 상태 어휘를 쓰지 않는다 —
	// 판정 근거가 세션 생존 하나뿐이라(inspectWorkDir 아래 isMainSessionAlive) 참·거짓이 그 사실 전부다.
	mainSessionAlive bool

	// mainHasRecordedConversation 은 메인 세션이 이어갈 대화가 적혀 있는지다.
	// 레포와 같은 사실을 메인 라벨에 대해 답한 것이다.
	mainHasRecordedConversation bool

	// planWarnings 는 계획을 그대로 쓰지 못한 이유들이다. 사람용 모드에서는 stderr 로 나가고,
	// JSON 모드에서는 문서의 한 필드로 들어간다.
	planWarnings []string
}

// displayRows 는 화면에 찍을 순서대로 행을 늘어놓는다.
//
// 레포는 ScanRepos 가 준 이름순 그대로이고 메인은 마지막이다. 메인은 레포가 아니라 그 묶음을
// 관장하는 자리라, 이름순에 섞여 들어가면 정렬 규칙이 깨진 것처럼 보인다.
func (listing workDirListing) displayRows() []listRow {
	rows := make([]listRow, 0, len(listing.repos)+1)
	for _, repo := range listing.repos {
		rows = append(rows, listRow{label: repo.name, state: repo.state, planNote: repo.planSummary.note()})
	}
	return append(rows, listing.mainDisplayRow())
}

// mainDisplayRow 는 메인 세션 행을 만든다.
//
// 계획 칸을 두지 않는다. 계획의 작업은 레포에서 수행하는 것이고(설계 문서 6.1 의 repo 필드),
// 메인 세션은 코드를 직접 고치지 않는 조율용 자리다(설계 문서 5.4).
func (listing workDirListing) mainDisplayRow() listRow {
	if listing.mainSessionAlive {
		return listRow{label: mainRowLabel, state: workdir.RepoStateRunning}
	}
	return listRow{label: mainRowLabel, state: workdir.RepoStateIdle}
}

// liveSessionCount 는 이 워크디렉토리에서 살아있는 세션 수를 센다(메인 포함).
//
// backend.List() 를 접두사로 걸러 세는 방법도 있지만, 그러면 디렉토리가 지워진 레포의 세션까지
// 잡혀 헤더의 숫자가 화면에 보이는 ● 개수와 어긋난다. 사용자가 직접 세어볼 수 있는 값과 같게 둔다.
func (listing workDirListing) liveSessionCount() int {
	liveCount := 0
	for _, row := range listing.displayRows() {
		if row.state == workdir.RepoStateRunning {
			liveCount++
		}
	}
	return liveCount
}

// inspectWorkDir 는 워크디렉토리를 훑어 레포별 상태와 메인 세션 상태를 모은다.
func inspectWorkDir(workDirPath string, backend session.SessionBackend) (workDirListing, error) {
	// 세션 이름에 워크디렉토리 이름이 들어가므로(kyu-<workdir>-<repo>) 이름을 먼저 확정해야 한다.
	// 인자로 흔히 들어오는 "." 이나 ".." 는 그 자체로는 이름이 아니라서, 절대경로로 편 뒤 마지막 요소를 쓴다.
	absoluteWorkDirPath, err := filepath.Abs(workDirPath)
	if err != nil {
		return workDirListing{}, fmt.Errorf("워크디렉토리 절대경로 변환 실패 (%s): %w", workDirPath, err)
	}
	workDirName := filepath.Base(absoluteWorkDirPath)

	repos, err := workdir.ScanRepos(absoluteWorkDirPath)
	if err != nil {
		return workDirListing{}, err
	}

	// 계획을 읽지 못한 것은 목록을 막지 않는다. LoadPlan 은 깨진 계획을 에러가 아니라 Warnings 로
	// 돌려주므로, 여기서 에러로 올라오는 것은 파일이 거기 있는데 읽지 못한 경우뿐이다.
	plan, err := workdir.LoadPlan(absoluteWorkDirPath)
	if err != nil {
		return workDirListing{}, err
	}

	// 대화 기록도 계획과 같은 자세로 읽는다 — 읽지 못한 기록은 "이어갈 대화 없음" 이고,
	// 파일이 거기 있는데 읽지 못한 것만 에러로 올라온다(workdir.LabelsWithRecordedConversation).
	labelsWithRecordedConversation, err := workdir.LabelsWithRecordedConversation(absoluteWorkDirPath)
	if err != nil {
		return workDirListing{}, err
	}

	observedRepos := make([]observedRepo, 0, len(repos))
	for _, repo := range repos {
		repoSessionName := session.RepoSessionName(workDirName, repo.Name)

		state, err := workdir.InferRepoState(repo, repoSessionName, backend)
		if err != nil {
			// 레포가 여러 개일 때 "git status 실패" 만으로는 어느 레포인지 알 수 없다.
			// 사용자가 아는 이름을 붙여야 그 디렉토리를 바로 열어볼 수 있다.
			return workDirListing{}, fmt.Errorf("레포 상태 판정 실패 (%s): %w", repo.Name, err)
		}

		observedRepos = append(observedRepos, observedRepo{
			name:                    repo.Name,
			absolutePath:            repo.AbsolutePath,
			state:                   state,
			planSummary:             summarizeRepoPlan(plan, repo.Name),
			hasRecordedConversation: labelsWithRecordedConversation[repo.Name],
		})
	}

	mainSessionAlive, err := isMainSessionAlive(workDirName, backend)
	if err != nil {
		return workDirListing{}, err
	}

	planWarnings := append(plan.Warnings, missingRepoWarnings(plan, repos, workdir.PlanFilePath(absoluteWorkDirPath))...)

	return workDirListing{
		workDirName:                 workDirName,
		workDirAbsolutePath:         absoluteWorkDirPath,
		repos:                       observedRepos,
		mainSessionAlive:            mainSessionAlive,
		mainHasRecordedConversation: labelsWithRecordedConversation[mainRowLabel],
		planWarnings:                planWarnings,
	}, nil
}

// isMainSessionAlive 는 메인 세션이 떠 있는지 묻는다.
//
// 레포와 달리 git 에 묻지 않는다. 메인 세션이 뜨는 곳은 워크디렉토리 최상위이고 그곳은 레포가 아니다
// (설계 문서 5.4). 거기에 굴러다니는 파일은 커밋할 대상이 아니므로 DIRTY 라고 말하면 거짓이 된다.
// 판정 근거는 세션 생존 하나뿐이다.
func isMainSessionAlive(workDirName string, backend session.SessionBackend) (bool, error) {
	mainSessionName := session.MainSessionName(workDirName)

	isAlive, err := backend.IsAlive(mainSessionName)
	if err != nil {
		return false, fmt.Errorf("메인 세션 생존 확인 실패 (%s): %w", mainSessionName, err)
	}
	return isAlive, nil
}

const (
	// runningSessionMarker 는 세션이 살아있는 행에 붙는 채운 동그라미다.
	//
	// 상태 낱말(RUNNING/DIRTY/...)은 오른쪽 열에 있어 한 줄씩 읽어야 눈에 들어오는데,
	// "지금 누가 어디에 붙어 있는가" 는 목록에서 가장 먼저 알고 싶은 것이라 왼쪽 끝에 그림으로 둔다.
	// 색을 쓰지 않는 이유는 파이프로 넘기거나 로그에 붙일 때 이스케이프 문자가 섞이기 때문이다.
	runningSessionMarker = "●"

	// noSessionMarker 는 세션이 없는 행에 붙는 빈 동그라미다. DIRTY·AHEAD·IDLE 이 모두 여기에 해당한다.
	noSessionMarker = "○"
)

// rowIndent 는 목록 행을 헤더보다 안쪽으로 들여쓴다. 헤더·안내문과 행 묶음을 눈으로 가르는 장치다.
const rowIndent = "  "

// tableColumnPadding 은 열 사이 최소 간격이다. tabwriter 가 열에서 가장 긴 칸에 이 값을 더해 너비를 잡는다.
const tableColumnPadding = 2

const emptyWorkDirGuidance = "레포 없음 — 이 디렉토리 아래에 git 레포를 클론하세요"

const attachGuidance = "kyu attach <repo> 로 진입"

// writeWorkDirListing 은 관찰 결과를 한 화면으로 옮긴다.
func writeWorkDirListing(out io.Writer, listing workDirListing) error {
	// 한 번에 조립해 한 번에 내보낸다. 중간중간 out 에 쓰면 쓰기 실패를 매 호출마다 확인해야 하고,
	// 확인을 빠뜨린 자리가 조용히 생긴다. 출력은 한 화면 분량이라 통째로 담아도 부담이 없다.
	var rendered bytes.Buffer

	fmt.Fprintf(&rendered, "WorkDir: %s   (%s, %s)\n\n",
		listing.workDirName,
		countWithEnglishPlural(len(listing.repos), "repo"),
		countWithEnglishPlural(listing.liveSessionCount(), "session"))

	if len(listing.repos) == 0 {
		// 빈 목록만 보여주면 사용자는 도구가 레포를 못 찾은 것인지 아직 아무것도 없는 것인지 모른다.
		// 메인 행은 그대로 이어 붙인다 — 레포가 하나도 없어도 메인 세션은 떠 있을 수 있다.
		fmt.Fprintf(&rendered, "%s%s\n\n", rowIndent, emptyWorkDirGuidance)
	}

	alignedRows, err := renderListingTable(listing.displayRows())
	if err != nil {
		return err
	}
	rendered.WriteString(alignedRows)

	fmt.Fprintf(&rendered, "\n%s\n", attachGuidance)

	if _, err := out.Write(rendered.Bytes()); err != nil {
		return fmt.Errorf("목록 출력 실패: %w", err)
	}
	return nil
}

// renderListingTable 은 행들을 열이 맞은 표로 만든다.
//
// 열 너비를 손으로 계산하지 않고 tabwriter 에 맡긴다. 레포 이름 길이는 워크디렉토리마다 다른데,
// 고정 너비로 잡으면 긴 이름에서 열이 밀리고 짧은 이름만 있을 때는 빈칸이 남는다.
func renderListingTable(rows []listRow) (string, error) {
	var alignedRows bytes.Buffer

	table := tabwriter.NewWriter(&alignedRows, 0, 0, tableColumnPadding, ' ', 0)
	for _, row := range rows {
		// 계획 칸을 비었더라도 반드시 적는다. 어떤 행에서 칸을 아예 빼면 tabwriter 가 거기서 열 블록을
		// 끊어버려, 계획이 있는 레포와 없는 레포가 섞였을 때 상태 열의 너비가 행마다 달라진다.
		fmt.Fprintf(table, "%s%s\t%s\t%s\t%s\n", rowIndent, sessionMarker(row.state), row.label, row.state, row.planNote)
	}
	if err := table.Flush(); err != nil {
		return "", fmt.Errorf("목록 표 정렬 실패: %w", err)
	}

	return trimLineEndPadding(alignedRows.String()), nil
}

// trimLineEndPadding 은 줄 끝에 남은 정렬용 공백을 걷어낸다.
//
// 계획 칸이 빈 행에서는 그 앞 열을 채운 공백이 줄 끝에 그대로 남는다. 화면에서는 보이지 않지만
// 출력을 파일로 넘기거나 붙여넣을 때 따라다니고, 계획 도입 전과 같은 출력이어야 할 자리를
// 눈에 보이지 않는 공백만큼 어긋나게 만든다.
func trimLineEndPadding(alignedRows string) string {
	lines := strings.Split(alignedRows, "\n")
	for lineIndex, line := range lines {
		lines[lineIndex] = strings.TrimRight(line, " ")
	}
	return strings.Join(lines, "\n")
}

func sessionMarker(state workdir.RepoState) string {
	if state == workdir.RepoStateRunning {
		return runningSessionMarker
	}
	return noSessionMarker
}

// countWithEnglishPlural 은 "3 repos" / "1 repo" 를 만든다.
//
// 헤더 문구는 설계 문서 9.3 의 예시를 그대로 따르는데, 개수와 무관하게 s 를 붙이면 "1 repos" 가 찍힌다.
// 목록을 열 때마다 보는 한 줄이라 그 어긋남을 남겨두지 않는다.
func countWithEnglishPlural(count int, singularNoun string) string {
	if count == 1 {
		return fmt.Sprintf("%d %s", count, singularNoun)
	}
	return fmt.Sprintf("%d %ss", count, singularNoun)
}
