package cli

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// 이 파일은 kyu clone 이다 — 워크디렉토리를 채우는 명령.
//
// 무엇을 클론할지 묻지 않는다. 목록을 보여주고 화살표로 고르게 하던 화면이 있었지만, 그 고르는
// 일은 이제 앱의 클론 다이얼로그가 한다 — 엔진은 앱이 이미 고른 것을 받아 클론하고 결과를
// 돌려준다(app-owned-sessions-design.md 6절). 무엇이 있는지 묻는 자리는 kyu repos 다.
const cloneUsageText = `사용법: kyu clone --profile <이름> --repo <owner/name> ... [--json]

  kyu clone --profile 개인 --repo maximinhan/proj-a --repo maximinhan/proj-b

옵션:
  --profile <이름>     어느 토큰으로 붙을지 (kyu auth list 가 이름을 보여준다)
  --repo <owner/name>  클론할 레포. 여러 번 적을 수 있다
  --json               사람용 출력 대신 기계용 JSON 을 낸다

무엇을 클론할지는 부르는 쪽이 정한다. 고를 후보를 묻는 자리는 kyu repos 다.`

// repoOptionName 은 클론할 레포를 지정하는 옵션이다. 여러 번 적을 수 있다.
const repoOptionName = "--repo"

// CloneRepos 는 kyu clone 을 실행한다: 저장된 토큰으로 붙어 적어 보낸 레포를 클론한다.
func CloneRepos(out io.Writer, args []string, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore) error {
	request, err := parseCloneArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	return cloneRequestedRepositories(out, request, location, newAccess, tokenStore)
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

// repositoryRow 는 클론 한 번의 대상이다.
type repositoryRow struct {
	repository github.Repository

	// destinationPath 는 이 레포가 놓일 절대경로다.
	//
	// 클론 시점에 다시 조립하면 "이미 있는가" 를 판정한 경로와 실제로 클론하는 경로가 갈릴 수
	// 있는데, 그 어긋남은 엉뚱한 자리에 레포가 생겨야 드러난다.
	destinationPath string

	// isAlreadyInWorkDir 는 그 자리에 이미 무언가 있는지다.
	isAlreadyInWorkDir bool
}

// repositoryRowFor 는 레포 하나를 클론 직전의 행으로 바꾼다.
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
