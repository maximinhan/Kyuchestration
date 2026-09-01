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
)

// 이 파일은 kyu clone 이다 — 워크디렉토리를 채우는 명령.
//
// 워크디렉토리는 "필요한 레포를 클론해서 쓴다" 가 전제인데(설계 문서 1.1), 그 클론만은 사용자가
// 손으로 하고 있었다. 이름을 하나씩 기억해 주소를 조립하는 일이라 오타가 나고, 무엇보다
// "이 계정에 어떤 레포가 있더라" 를 GitHub 화면에서 확인하고 돌아와야 한다.
const cloneUsageText = `사용법: kyu clone [--profile <이름> --repo <owner/name> ...] [--json]

  kyu clone                                          GitHub 레포 목록에서 화살표로 골라 클론한다
  kyu clone --profile <이름> --repo <owner/name>      묻지 않고 그 레포를 클론한다 (--repo 는 여러 번)

옵션:
  --profile <이름>   어느 토큰으로 붙을지 (묻지 않는 실행에 필수)
  --repo <owner/name>  클론할 레포. 여러 번 적을 수 있다
  --json             사람용 출력 대신 기계용 JSON 을 낸다 (--repo 와 함께 쓴다)

토큰은 처음 실행할 때 물어보고 이 머신에 저장한다 (kyu auth add 로 미리 등록할 수도 있다).`

// repoOptionName 은 묻지 않고 클론할 레포를 지정하는 옵션이다. 여러 번 적을 수 있다.
const repoOptionName = "--repo"

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

// CloneRepos 는 kyu clone 을 실행한다: 토큰 프로필 선택 → 소유자 선택 → 레포 목록 → 클론.
//
// 입력을 io.Reader 로 받는 이유는 테스트다. 이 명령은 처음부터 끝까지 대화이고, 그 대화가
// 실제 터미널에서만 돌아간다면 검증할 수 있는 것은 아무것도 남지 않는다.
func CloneRepos(in io.Reader, out, errOut io.Writer, args []string, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore) error {
	request, err := parseCloneArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	// 무엇을 클론할지 이미 적어 보냈으면 아무것도 묻지 않는다. 그 갈림이 이 명령의 두 사용처를
	// 가른다 — 사람은 목록을 보며 고르고, GUI 는 자기 화면에서 이미 고른 것을 건넨다.
	if len(request.repositoryReferences) > 0 {
		return cloneWithoutAsking(out, request, location, newAccess, tokenStore)
	}

	return cloneByAsking(in, out, errOut, location, newAccess, tokenStore)
}

// cloneByAsking 은 목록을 보여주고 고르게 해서 클론한다 — 사람이 지나는 길이다.
func cloneByAsking(in io.Reader, out, errOut io.Writer, location workDirLocation, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore) error {
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

	selectedRows := make([]repositoryRow, 0, len(selectedIndexes))
	for _, selectedIndex := range selectedIndexes {
		selectedRows = append(selectedRows, rows[selectedIndex])
	}

	attempts := cloneEachRow(access, selectedRows)
	writeCloneAttempts(out, attempts)

	return failedCloneError(attempts)
}

// failedCloneError 는 실패한 레포가 있으면 그것을 종료 코드로 옮길 에러를 만든다.
//
// 종료 코드로 실패를 알린다. 화면의 줄들은 스크롤 위로 사라지지만 종료 코드는 남고,
// 이 명령을 앞에 둔 스크립트가 보는 것도 그것이다.
func failedCloneError(attempts []cloneAttempt) error {
	var failedLabels []string
	for _, attempt := range attempts {
		if attempt.status == cloneAttemptFailed {
			failedLabels = append(failedLabels, attempt.label)
		}
	}
	if len(failedLabels) == 0 {
		return nil
	}
	return fmt.Errorf("클론 실패: %s", strings.Join(failedLabels, ", "))
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
//
// 목록을 모으는 일은 kyu repos owners 와 같은 함수에 맡긴다. 어느 문으로 들어왔든 사용자가
// 보게 되는 후보가 같아야 한다 — 특히 fine-grained 토큰의 403 을 통과시키는 규칙이 그렇다.
func chooseRepositoryOwner(prompt *interactivePrompt, errOut io.Writer, access github.RepositoryAccess, personalOwner github.Owner) (github.Owner, error) {
	owners, err := listOwnersTheTokenCanSee(errOut, access, personalOwner)
	if err != nil {
		return github.Owner{}, err
	}

	// 고를 것이 하나뿐인 물음은 묻지 않는다. 물으면 사용자는 매번 1 을 치게 된다.
	if len(owners) == 1 {
		return owners[0], nil
	}

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
		rows = append(rows, repositoryRowFor(repository, absoluteWorkDirPath))
	}
	return rows
}

// repositoryRowFor 는 레포 하나를 클론 직전의 행으로 바꾼다.
//
// 묻지 않는 클론도 이 함수를 쓴다. "이미 있으면 건너뛴다" 는 판정이 두 길에서 갈리면, 같은
// 워크디렉토리에 같은 레포를 두고도 어느 문으로 들어왔는지에 따라 결과가 달라진다.
func repositoryRowFor(repository github.Repository, absoluteWorkDirPath string) repositoryRow {
	destinationPath := filepath.Join(absoluteWorkDirPath, repository.Name)

	// 디렉토리인지 파일인지 따지지 않는다. 그 이름이 이미 쓰이고 있으면 git clone 은 어차피
	// 실패하고, 이 도구가 그 자리의 것을 지울 일은 없다.
	_, statErr := os.Stat(destinationPath)

	return repositoryRow{
		repository:         repository,
		destinationPath:    destinationPath,
		isAlreadyInWorkDir: statErr == nil,
	}
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

// cloneAttemptStatus 는 레포 하나가 어떻게 끝났는지다. 기계용 문서의 status 가 곧 이 값이다.
type cloneAttemptStatus string

const (
	cloneAttemptCloned  cloneAttemptStatus = "cloned"
	cloneAttemptSkipped cloneAttemptStatus = "skipped"
	cloneAttemptFailed  cloneAttemptStatus = "failed"
)

// cloneAttempt 는 레포 하나에 대한 시도와 그 결말이다.
//
// 화면 문구를 담지 않는다. 이 값 하나에서 사람용 줄도, 기계용 문서의 항목도 나오는데,
// 실행 단계에서 문자열로 굳혀버리면 뒤에 오는 출력은 그 문자열을 되파싱하는 수밖에 없다.
type cloneAttempt struct {
	// label 은 사용자가 이 레포를 부른 이름이다 — 대화형은 목록에 찍힌 레포 이름, 묻지 않는
	// 실행은 --repo 에 적은 owner/name. 앱이 자기가 보낸 것과 돌아온 것을 이 값으로 맞춘다.
	label string

	status cloneAttemptStatus

	// message 는 왜 그렇게 끝났는지다. 클론된 레포에서는 비어 있다 — 성공에 덧붙일 이유가 없다.
	message string
}

// alreadyInWorkDirMessage 는 그 자리에 이미 무언가 있어 건너뛴 이유다.
const alreadyInWorkDirMessage = "같은 이름의 디렉토리가 이미 있습니다"

// cloneEachRow 는 행을 받은 순서대로 클론하고 각각의 결말을 모은다.
//
// 하나가 실패해도 멈추지 않는다. 멈추면 사용자는 남은 레포를 손으로 클론해야 하는데, 실패의
// 흔한 이유(그 레포에 권한이 없다)는 다른 레포와 상관이 없다.
func cloneEachRow(access github.RepositoryAccess, rows []repositoryRow) []cloneAttempt {
	attempts := make([]cloneAttempt, 0, len(rows))
	for _, row := range rows {
		attempts = append(attempts, cloneOneRow(access, row.repository.Name, row))
	}
	return attempts
}

// cloneOneRow 는 행 하나를 클론하고 그 결말을 돌려준다.
func cloneOneRow(access github.RepositoryAccess, label string, row repositoryRow) cloneAttempt {
	if row.isAlreadyInWorkDir {
		return cloneAttempt{label: label, status: cloneAttemptSkipped, message: alreadyInWorkDirMessage}
	}

	if err := access.CloneRepository(row.repository, row.destinationPath); err != nil {
		return cloneAttempt{label: label, status: cloneAttemptFailed, message: err.Error()}
	}
	return cloneAttempt{label: label, status: cloneAttemptCloned}
}

// writeCloneAttempts 는 결말들을 사람이 읽는 줄로 옮기고 마지막에 요약 한 줄을 붙인다.
//
// 요약을 붙이는 이유: 레포가 많으면 위의 줄들은 화면 밖으로 밀려난다. 사용자가 마지막에 보는
// 한 줄이 결과여야 한다.
func writeCloneAttempts(out io.Writer, attempts []cloneAttempt) {
	fmt.Fprintln(out)

	clonedCount, skippedCount, failedCount := 0, 0, 0
	for _, attempt := range attempts {
		switch attempt.status {
		case cloneAttemptCloned:
			clonedCount++
			fmt.Fprintf(out, "%s%s 클론 완료\n", rowIndent, attempt.label)
		case cloneAttemptSkipped:
			skippedCount++
			fmt.Fprintf(out, "%s%s 는 이미 있어 건너뜁니다.\n", rowIndent, attempt.label)
		case cloneAttemptFailed:
			failedCount++
			// 실패한 이유를 그 자리에서 보여준다. 마지막 요약에는 개수만 남으므로, 무엇 때문에
			// 실패했는지는 여기서만 알 수 있다.
			fmt.Fprintf(out, "%s%s 클론 실패: %s\n", rowIndent, attempt.label, attempt.message)
		}
	}

	parts := []string{fmt.Sprintf("클론 %d 개", clonedCount)}
	if skippedCount > 0 {
		parts = append(parts, fmt.Sprintf("건너뜀 %d 개", skippedCount))
	}
	if failedCount > 0 {
		parts = append(parts, fmt.Sprintf("실패 %d 개", failedCount))
	}
	fmt.Fprintf(out, "\n%s\n", strings.Join(parts, ", "))
}
