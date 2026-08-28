package cli

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"text/tabwriter"

	"golang.org/x/term"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
	"github.com/maximinhan/Kyuchestration/internal/session"
)

// 이 파일은 kyu clone 이다 — 워크디렉토리를 채우는 명령.
//
// 워크디렉토리는 "필요한 레포를 클론해서 쓴다" 가 전제인데(설계 문서 1.1), 그 클론만은 사용자가
// 손으로 하고 있었다. 이름을 하나씩 기억해 주소를 조립하는 일이라 오타가 나고, 무엇보다
// "이 계정에 어떤 레포가 있더라" 를 GitHub 화면에서 확인하고 돌아와야 한다.
//
// 진입 플로우(인자 없는 kyu)에 끼우지 않는다. 클론은 워크디렉토리를 만들 때 한 번 하는 일이고,
// 매번 진입할 때마다 GitHub 에 붙어 목록을 묻는 것은 사용자가 원한 적 없는 왕복이다.

const cloneUsageText = `사용법: kyu clone

  kyu clone   GitHub 레포 목록에서 화살표로 골라 이 워크디렉토리에 클론한다

토큰은 처음 실행할 때 물어보고 이 머신에 저장한다 (kyu auth list 로 확인).`

// repositorySelectionQuestion 은 무엇을 클론할지 묻는 말이다.
const repositorySelectionQuestion = "\n클론할 레포 (1,3,5-8 / 빈 줄이면 취소): "

// alreadyInWorkDirNote 는 같은 이름의 디렉토리가 이미 있는 레포에 붙는 표시다.
const alreadyInWorkDirNote = "(이미 있음)"

// privateRepositoryMark 는 비공개 레포에 붙는 표시다.
//
// 공개 레포에는 아무것도 붙이지 않는다. 둘 다 표시하면 목록이 빽빽해지는데, 사용자가 알아야
// 하는 것은 "이 중에 비공개가 무엇인가" 쪽이다.
const privateRepositoryMark = "private"

// unknownUpdatedDateMark 는 갱신 시각을 받지 못한 레포의 날짜 자리에 들어간다.
const unknownUpdatedDateMark = "-"

// updatedDateLayout 은 목록에 찍는 마지막 갱신 날짜의 형식이다.
//
// 시각까지 보여주지 않는다. 목록에서 하는 판단은 "최근에 만졌는가" 이고, 그 판단에 분 단위는
// 필요 없는데 열은 그만큼 넓어진다.
const updatedDateLayout = "2006-01-02"

// newReposNeedASessionRestartWarning 은 방금 클론한 레포가 떠 있는 메인 세션에 닿지 않는다는 안내다.
//
// 세션이 실행할 명령은 세션을 만드는 순간 정해지고 그 뒤로 바뀌지 않는다(PR 10·12 와 같은 자리).
// 조용히 넘어가면 사용자는 방금 클론한 레포가 세션에서 왜 안 보이는지 알 수 없다.
const newReposNeedASessionRestartWarning = "새로 클론한 레포는 이미 떠 있는 메인 세션에 붙지 않습니다 — 세션의 --add-dir 목록은 만들 때 정해집니다.\n" +
	"반영하려면 kyu kill main 뒤에 kyu 를 다시 실행하세요.\n"

// CloneRepos 는 kyu clone 을 실행한다: 토큰 프로필 선택 → 소유자 선택 → 레포 목록 → 클론.
//
// 입력을 io.Reader 로 받는 이유는 테스트다. 이 명령은 처음부터 끝까지 대화이고, 그 대화가
// 실제 터미널에서만 돌아간다면 검증할 수 있는 것은 아무것도 남지 않는다.
//
// 세션 백엔드를 받는 이유는 마지막 한 줄 때문이다 — 클론이 끝난 뒤 떠 있는 메인 세션이 있는지
// 물어야, 새 레포가 그 세션에 닿지 않는다는 사실을 그 자리에서 알릴 수 있다.
func CloneRepos(in io.Reader, out, errOut io.Writer, args []string, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore, backend session.SessionBackend) error {
	if len(args) != 0 {
		return fmt.Errorf("clone 은 인자를 받지 않습니다 (인자 %d 개를 받음)\n\n%s", len(args), cloneUsageText)
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	prompt := newInteractivePrompt(in, out)

	access, personalOwner, err := authenticateWithTokenProfile(prompt, errOut, tokenStore, newAccess)
	if err != nil {
		return cancellationOrError(out, err)
	}

	owner, err := chooseRepositoryOwner(prompt, errOut, access, personalOwner)
	if err != nil {
		return cancellationOrError(out, err)
	}

	repositories, err := access.Repositories(owner)
	if err != nil {
		return err
	}
	if len(repositories) == 0 {
		// 번호를 물어봐야 답할 것이 없다. 빈 목록만 보여주면 도구가 못 찾은 것인지 정말 없는
		// 것인지도 알 수 없다.
		fmt.Fprintf(out, "\n%s 에 클론할 레포가 없습니다.\n", owner.Login)
		return nil
	}

	rows := repositoryRowsFor(repositories, location.absolutePath)

	selectedIndexes, err := chooseRepositoriesToClone(prompt, out, owner, rows)
	if err != nil {
		return cancellationOrError(out, err)
	}
	if len(selectedIndexes) == 0 {
		fmt.Fprintln(out, "취소했습니다.")
		return nil
	}

	result := cloneSelectedRepositories(out, access, rows, selectedIndexes)
	writeCloneSummary(out, result)

	// 실패가 섞여 있어도 안내는 한다. 하나라도 클론됐다면 세션이 보는 레포 목록은 이미 어긋나 있다.
	if result.clonedCount > 0 {
		if err := warnNewReposDoNotReachRunningMainSession(errOut, location.name, backend); err != nil {
			return err
		}
	}

	if len(result.failedRepositoryNames) > 0 {
		// 종료 코드로 실패를 알린다. 화면의 줄들은 스크롤 위로 사라지지만 종료 코드는 남는다.
		return fmt.Errorf("클론 실패: %s", strings.Join(result.failedRepositoryNames, ", "))
	}
	return nil
}

// chooseRepositoriesToClone 는 무엇을 클론할지 고르게 하고 고른 줄의 인덱스를 돌려준다.
//
// 실제 터미널이면 화살표·검색 화면을, 아니면 번호를 적는 방식을 쓴다. 갈림의 근거는 TUI 가
// 터미널을 필요로 한다는 것이다 — 화면을 지웠다 다시 그리려면 커서를 옮기는 이스케이프를
// 내보내고 키를 한 글자씩 받아야 하는데, 파이프로 넘어온 입력에는 그럴 상대가 없고 그 이스케이프는
// 기록에 그대로 섞인다.
//
// 그래서 번호 방식을 지우지 않고 남긴다. 파이프로 답을 넘기는 실행(테스트·스크립트)은 이 명령이
// 처음부터 지원하던 사용법이고, 화면을 바꿨다고 그것이 사라져서는 안 된다.
//
// 입력과 출력을 둘 다 본다. 한쪽만 터미널인 실행(kyu clone > log.txt)에서 화면을 그리면
// 사용자는 아무것도 못 보고, 파일에는 이스케이프 뭉치만 남는다.
func chooseRepositoriesToClone(prompt *interactivePrompt, out io.Writer, owner github.Owner, rows []repositoryRow) ([]int, error) {
	if prompt.inputFile != nil && isTerminalWriter(out) {
		return pickRepositoriesWithArrowKeys(prompt.inputFile, out, owner, rows)
	}

	if err := writeRepositoryListing(out, owner, rows); err != nil {
		return nil, err
	}

	answer, err := prompt.ask(repositorySelectionQuestion)
	if err != nil {
		return nil, err
	}
	return parseRepositorySelection(answer, len(rows))
}

// isTerminalWriter 는 이 출력이 실제 터미널인지다.
func isTerminalWriter(out io.Writer) bool {
	outputFile, isFile := out.(*os.File)
	return isFile && term.IsTerminal(int(outputFile.Fd()))
}

// cancellationOrError 는 사용자의 취소를 성공으로, 나머지 실패는 그대로 돌려준다.
//
// 목록을 보고 그만두는 것은 실패가 아니다. 종료 코드 1 로 끝내면 kyu clone 을 앞에 둔 스크립트가
// 그것을 실패로 읽는다.
func cancellationOrError(out io.Writer, err error) error {
	if errors.Is(err, errInputClosed) {
		fmt.Fprintln(out, "취소했습니다.")
		return nil
	}
	return err
}

// chooseRepositoryOwner 는 개인 계정과 소속 조직 중 어느 것의 레포를 볼지 고르게 한다.
func chooseRepositoryOwner(prompt *interactivePrompt, errOut io.Writer, access github.RepositoryAccess, personalOwner github.Owner) (github.Owner, error) {
	organizations, err := access.Organizations()

	// fine-grained 토큰은 조직 목록에 권한이 없으면 403 으로 답한다. 그것으로 명령을 끝내면
	// 개인 레포를 클론하려던 사용자가 아무것도 하지 못한다 — 이 실패만 통과시킨다.
	if errors.Is(err, github.ErrForbidden) {
		fmt.Fprintf(errOut, "이 토큰으로는 조직 목록을 볼 수 없습니다 — %s 계정의 레포만 보여줍니다.\n", personalOwner.Login)
		return personalOwner, nil
	}
	if err != nil {
		return github.Owner{}, err
	}

	// 고를 것이 하나뿐인 물음은 묻지 않는다. 물으면 사용자는 매번 1 을 치게 된다.
	if len(organizations) == 0 {
		return personalOwner, nil
	}

	owners := make([]github.Owner, 0, len(organizations)+1)
	owners = append(owners, personalOwner)
	owners = append(owners, organizations...)

	fmt.Fprintf(prompt.out, "\n어느 계정의 레포를 볼까요.\n\n")
	table := tabwriter.NewWriter(prompt.out, 0, 0, tableColumnPadding, ' ', 0)
	for ownerNumber, candidate := range owners {
		fmt.Fprintf(table, "%s%d)\t%s\t%s\n", rowIndent, ownerNumber+1, candidate.Login, ownerKindLabel(candidate))
	}
	if err := table.Flush(); err != nil {
		return github.Owner{}, fmt.Errorf("소유자 표 정렬 실패: %w", err)
	}

	chosenIndex, err := prompt.askForNumberedChoice("\n번호: ", len(owners))
	if err != nil {
		return github.Owner{}, err
	}
	return owners[chosenIndex], nil
}

func ownerKindLabel(owner github.Owner) string {
	if owner.IsOrganization {
		return "조직"
	}
	return "개인"
}

// repositoryRow 는 목록의 한 줄이자 클론 한 번의 대상이다.
type repositoryRow struct {
	repository github.Repository

	// destinationPath 는 이 레포가 놓일 절대경로다.
	//
	// 화면에 찍히지는 않지만 행이 들고 있다. 클론 시점에 다시 조립하면 "이미 있는가" 를 판정한
	// 경로와 실제로 클론하는 경로가 갈릴 수 있는데, 그 어긋남은 엉뚱한 자리에 레포가 생겨야 드러난다.
	destinationPath string

	// isAlreadyInWorkDir 는 그 자리에 이미 무언가 있는지다.
	isAlreadyInWorkDir bool
}

// repositoryRowsFor 는 레포 목록을 화면에 옮기기 직전의 행으로 바꾼다.
//
// 이미 있는지를 여기서 한 번에 판정한다. 클론 직전에 확인하면 목록에는 표시가 없다가 실행 중에만
// 건너뛰게 되어, 사용자는 자기가 고른 것과 다른 결과를 보게 된다.
func repositoryRowsFor(repositories []github.Repository, absoluteWorkDirPath string) []repositoryRow {
	rows := make([]repositoryRow, 0, len(repositories))
	for _, repository := range repositories {
		destinationPath := filepath.Join(absoluteWorkDirPath, repository.Name)

		// 디렉토리인지 파일인지 따지지 않는다. 그 이름이 이미 쓰이고 있으면 git clone 은 어차피
		// 실패하고, 이 도구가 그 자리의 것을 지울 일은 없다.
		_, statErr := os.Stat(destinationPath)

		rows = append(rows, repositoryRow{
			repository:         repository,
			destinationPath:    destinationPath,
			isAlreadyInWorkDir: statErr == nil,
		})
	}
	return rows
}

// writeRepositoryListing 은 고를 수 있는 레포를 번호와 함께 보여준다.
func writeRepositoryListing(out io.Writer, owner github.Owner, rows []repositoryRow) error {
	var rendered bytes.Buffer
	fmt.Fprintf(&rendered, "\n%s 의 레포 %d 개 — 최근 갱신 순\n\n", owner.Login, len(rows))

	table := tabwriter.NewWriter(&rendered, 0, 0, tableColumnPadding, ' ', 0)
	for rowIndex, row := range rows {
		fmt.Fprintf(table, "%s%d)\t%s\t%s\t%s\t%s\n",
			rowIndent, rowIndex+1,
			row.repository.Name,
			visibilityMark(row.repository),
			updatedDate(row.repository),
			alreadyThereNote(row))
	}
	if err := table.Flush(); err != nil {
		return fmt.Errorf("레포 표 정렬 실패: %w", err)
	}

	if _, err := out.Write([]byte(trimLineEndPadding(rendered.String()))); err != nil {
		return fmt.Errorf("레포 목록 출력 실패: %w", err)
	}
	return nil
}

func visibilityMark(repository github.Repository) string {
	if repository.IsPrivate {
		return privateRepositoryMark
	}
	return ""
}

func updatedDate(repository github.Repository) string {
	if repository.LastUpdatedAt.IsZero() {
		return unknownUpdatedDateMark
	}
	return repository.LastUpdatedAt.Local().Format(updatedDateLayout)
}

func alreadyThereNote(row repositoryRow) string {
	if row.isAlreadyInWorkDir {
		return alreadyInWorkDirNote
	}
	return ""
}

// cloneOutcome 은 클론을 마친 뒤의 결과다.
type cloneOutcome struct {
	clonedCount           int
	skippedCount          int
	failedRepositoryNames []string
}

// cloneSelectedRepositories 는 고른 레포를 목록 순서대로 클론한다.
//
// 하나가 실패해도 멈추지 않는다. 멈추면 사용자는 남은 레포를 손으로 클론해야 하는데, 실패의
// 흔한 이유(그 레포에 권한이 없다)는 다른 레포와 상관이 없다.
func cloneSelectedRepositories(out io.Writer, access github.RepositoryAccess, rows []repositoryRow, selectedIndexes []int) cloneOutcome {
	var outcome cloneOutcome

	fmt.Fprintln(out)
	for _, selectedIndex := range selectedIndexes {
		row := rows[selectedIndex]
		repositoryName := row.repository.Name

		if row.isAlreadyInWorkDir {
			fmt.Fprintf(out, "%s%s 는 이미 있어 건너뜁니다.\n", rowIndent, repositoryName)
			outcome.skippedCount++
			continue
		}

		if err := access.CloneRepository(row.repository, row.destinationPath); err != nil {
			// 실패한 이유를 그 자리에서 보여준다. 마지막 요약에는 이름만 남으므로, 무엇 때문에
			// 실패했는지는 여기서만 알 수 있다.
			fmt.Fprintf(out, "%s%s 클론 실패: %v\n", rowIndent, repositoryName, err)
			outcome.failedRepositoryNames = append(outcome.failedRepositoryNames, repositoryName)
			continue
		}

		fmt.Fprintf(out, "%s%s 클론 완료\n", rowIndent, repositoryName)
		outcome.clonedCount++
	}
	return outcome
}

// writeCloneSummary 는 무엇이 몇 개였는지 한 줄로 모은다.
//
// 레포가 많으면 위의 줄들은 화면 밖으로 밀려난다. 사용자가 마지막에 보는 한 줄이 결과여야 한다.
func writeCloneSummary(out io.Writer, outcome cloneOutcome) {
	parts := []string{fmt.Sprintf("클론 %d 개", outcome.clonedCount)}
	if outcome.skippedCount > 0 {
		parts = append(parts, fmt.Sprintf("건너뜀 %d 개", outcome.skippedCount))
	}
	if len(outcome.failedRepositoryNames) > 0 {
		parts = append(parts, fmt.Sprintf("실패 %d 개", len(outcome.failedRepositoryNames)))
	}
	fmt.Fprintf(out, "\n%s\n", strings.Join(parts, ", "))
}

// warnNewReposDoNotReachRunningMainSession 은 떠 있는 메인 세션이 새 레포를 보지 못한다는 사실을 알린다.
func warnNewReposDoNotReachRunningMainSession(errOut io.Writer, workDirName string, backend session.SessionBackend) error {
	mainSessionName := session.MainSessionName(workDirName)

	isAlive, err := backend.IsAlive(mainSessionName)
	if err != nil {
		return fmt.Errorf("메인 세션 생존 확인 실패 (%s): %w", mainSessionName, err)
	}
	// 떠 있지도 않은 세션을 다시 띄우라는 안내는 사용자를 헷갈리게 한다. 다음에 kyu 를 실행하면
	// 그때 스캔한 목록으로 세션이 뜬다.
	if !isAlive {
		return nil
	}

	if _, err := fmt.Fprint(errOut, newReposNeedASessionRestartWarning); err != nil {
		return fmt.Errorf("세션 재시작 안내 출력 실패: %w", err)
	}
	return nil
}
