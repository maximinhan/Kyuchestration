package workdir

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// 이 파일은 위임의 원출력 기록이다 — .coord/runs/ (orchestration-tools-design.md 5.4.3).
//
// 왜 남기는가: 위임은 이 도구가 사용자를 대신해 외부 명령을 실행하는 자리이고, 그 결과가 도구의
// 답 안에서 요약되어 사라지면 나중에 "그때 무엇이 거절됐나" 를 알 길이 없다.
//
// 왜 .coord 인가: 워크디렉토리를 버리면 함께 사라지고, 옮기면 함께 가고, 사람이 열어볼 수 있다.
// 레포 안에는 넣지 않는다(설계 원칙 2) — 팀 공용 레포에 우리 도구의 기록이 남으면 안 된다.
//
// 정리 규칙을 v1 에 두지 않는다. 워크디렉토리가 버려지는 물건이라 함께 사라진다. 실제로 쌓여서
// 불편해지면 그때 정한다.

// runsDirectoryName 은 원출력을 모아 두는 디렉토리다. 자리는 계획·대화 기록과 같은 .coord 다.
const runsDirectoryName = "runs"

// delegationRunFileSchemaVersion 은 기록 파일 형식의 판이다.
//
// 판 규약은 다른 문서와 같다 — 필드를 더하는 변경으로는 올리지 않고, 필드를 빼거나 이름·의미를
// 바꿀 때만 올린다.
const delegationRunFileSchemaVersion = 1

// delegationRunFilePermission 은 기록 파일의 권한이다. 사람이 열어볼 수 있어야 한다.
const delegationRunFilePermission = 0o644

// delegationRunFileTimeLayout 은 파일 이름에 쓰는 시각 모양이다.
//
// 사전순이 곧 시간순이 되도록 자릿수를 고정한다 — 가장 최근 위임을 찾는 일이 이름 정렬 하나로
// 끝난다(MostRecentDelegation).
const delegationRunFileTimeLayout = "20060102-150405"

// delegationRun 은 위임 한 번의 기록 한 벌이다.
//
// claude 의 답 원문을 그대로 담되(Output), 그것만으로는 답할 수 없는 사실을 겉에 함께 적는다.
// 종료 코드와 시각이 그렇다 — 답 문서 안에는 없고, 카드가 최근 위임을 보이려면 필요하다(6.1).
type delegationRun struct {
	SchemaVersion int `json:"schemaVersion"`

	Repo           string `json:"repo"`
	ConversationID string `json:"conversationId"`
	Resumed        bool   `json:"resumed"`

	StartedAt  string `json:"startedAt"`
	FinishedAt string `json:"finishedAt"`

	ExitCode int  `json:"exitCode"`
	TimedOut bool `json:"timedOut"`

	// HadPermissionDenials 는 권한에 막힌 도구가 있었는지다.
	//
	// Output 안에 이미 있는 사실을 겉에 한 번 더 적는다. 목록은 워크디렉토리를 열어둔 동안
	// 되풀이되는 물음이라, 그때마다 답 문서 전체를 파싱하게 두지 않는다.
	HadPermissionDenials bool `json:"hadPermissionDenials"`

	// Command 는 실제로 실행한 인자다. 어떤 권한 모드로 돌았는지가 여기 남는다.
	Command []string `json:"command"`

	// Prompt 는 메인이 그 레포에 시킨 일이다. 인자가 아니라 stdin 으로 갔으므로 Command 에 없다.
	Prompt string `json:"prompt"`

	Stderr string `json:"stderr"`

	// Output 은 claude 의 --output-format json 전문이다. 우리가 읽지 않는 키까지 그대로 남는다.
	Output json.RawMessage `json:"output,omitempty"`

	// Stdout 은 Output 으로 읽지 못했을 때의 원문이다. 읽지 못한 답을 버리면 왜 실패했는지를
	// 알아낼 유일한 재료가 사라진다.
	Stdout string `json:"stdout,omitempty"`
}

// recordDelegationRun 은 위임 한 번을 .coord/runs/ 에 적고 그 파일 경로를 돌려준다.
func recordDelegationRun(
	workDirPath string,
	request DelegationRequest,
	outcome DelegationOutcome,
	commandArguments []string,
	stdout []byte,
	stdoutIsJSON bool,
	startedAt time.Time,
) (string, error) {
	finishedAt := startedAt.Add(outcome.Elapsed)

	run := delegationRun{
		SchemaVersion:        delegationRunFileSchemaVersion,
		Repo:                 request.Repo.Name,
		ConversationID:       request.ConversationID,
		Resumed:              request.ResumeConversation,
		StartedAt:            startedAt.UTC().Format(time.RFC3339),
		FinishedAt:           finishedAt.UTC().Format(time.RFC3339),
		ExitCode:             outcome.ExitCode,
		TimedOut:             outcome.TimedOut,
		HadPermissionDenials: len(outcome.PermissionDeniedTools) > 0,
		Command:              append([]string{delegatedCommandName}, commandArguments...),
		Prompt:               request.Prompt,
		Stderr:               outcome.Stderr,
	}
	if stdoutIsJSON {
		run.Output = json.RawMessage(stdout)
	} else {
		run.Stdout = string(stdout)
	}

	runsDirectoryPath := filepath.Join(workDirPath, coordDirectoryName, runsDirectoryName)
	if err := os.MkdirAll(runsDirectoryPath, CoordDirectoryPermission); err != nil {
		return "", fmt.Errorf("위임 기록 디렉토리 생성 실패 (%s): %w", runsDirectoryPath, err)
	}

	document, err := json.MarshalIndent(run, "", "  ")
	if err != nil {
		return "", fmt.Errorf("위임 기록 조립 실패 (%s): %w", request.Repo.Name, err)
	}
	document = append(document, '\n')

	runFilePath := filepath.Join(runsDirectoryPath, delegationRunFileName(request, finishedAt))
	if err := os.WriteFile(runFilePath, document, delegationRunFilePermission); err != nil {
		return "", fmt.Errorf("위임 기록 쓰기 실패 (%s): %w", runFilePath, err)
	}
	return runFilePath, nil
}

// abbreviatedConversationIDLength 는 파일 이름에 싣는 대화 ID 의 길이다.
//
// 같은 초에 끝난 두 위임을 가르는 것이 이 조각이다. 전체를 싣지 않는 이유는 파일 이름이
// 사람이 눈으로 훑는 것이어서다.
const abbreviatedConversationIDLength = 8

// delegationRunFileName 은 <레포>-<시각>-<대화ID 앞 8 자>.json 을 만든다(설계 문서 5.4.3).
func delegationRunFileName(request DelegationRequest, finishedAt time.Time) string {
	abbreviatedConversationID := request.ConversationID
	if len(abbreviatedConversationID) > abbreviatedConversationIDLength {
		abbreviatedConversationID = abbreviatedConversationID[:abbreviatedConversationIDLength]
	}

	return fmt.Sprintf("%s-%s-%s.json",
		request.Repo.Name, finishedAt.UTC().Format(delegationRunFileTimeLayout), abbreviatedConversationID)
}

// RecentDelegation 은 한 레포에서 가장 최근에 끝난 위임에 대해 답할 수 있는 것이다(설계 문서 6.1).
type RecentDelegation struct {
	FinishedAt           string
	ExitCode             int
	TimedOut             bool
	HadPermissionDenials bool
	LogPath              string
}

// MostRecentDelegation 은 이 레포의 마지막 위임을 찾는다. 위임한 적이 없으면 nil 이다.
//
// 별도의 상태 파일을 두지 않는다. 원출력 기록이 이미 그 자리에 있고, 그것을 읽는 것이 곧
// 관찰이다(설계 원칙 6). 도는 중인 위임은 여기에 없다 — 시작할 때 무언가를 적으면 그것은
// 선언된 상태가 되고, 그 프로세스가 SIGKILL 로 죽는 순간부터 거짓말을 한다(설계 문서 6.2).
func MostRecentDelegation(workDirPath, repoName string) (*RecentDelegation, error) {
	runsDirectoryPath := filepath.Join(workDirPath, coordDirectoryName, runsDirectoryName)

	entries, err := os.ReadDir(runsDirectoryPath)
	if errors.Is(err, os.ErrNotExist) {
		// 이 워크디렉토리에서 아직 아무 레포에도 위임한 적이 없다. 흔한 정상 상태다.
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("위임 기록 디렉토리 읽기 실패 (%s): %w", runsDirectoryPath, err)
	}

	// os.ReadDir 은 이름순으로 준다. 파일 이름의 시각이 고정 자릿수라 이름순이 곧 시간순이고,
	// 뒤에서부터 보면 가장 최근 것을 먼저 만난다.
	for entryIndex := len(entries) - 1; entryIndex >= 0; entryIndex-- {
		entry := entries[entryIndex]

		// 이름만으로 고르지 않는다. proj 와 proj-a 가 함께 있으면 proj- 접두사가 둘 다 걸린다 —
		// 접두사는 파일을 덜 열기 위한 거름망이고, 어느 레포의 기록인지는 문서 안의 값이 답한다.
		if entry.IsDir() || !strings.HasPrefix(entry.Name(), repoName+"-") {
			continue
		}

		runFilePath := filepath.Join(runsDirectoryPath, entry.Name())
		run, err := readDelegationRun(runFilePath)
		if err != nil || run.Repo != repoName {
			// 읽지 못한 기록은 건너뛴다. 그것 하나 때문에 목록이 멈추면, 사용자는 자기가 쓴 적도
			// 없는 파일을 손으로 지우기 전까지 카드를 보지 못한다 — 계획·대화 기록과 같은 자세다.
			continue
		}

		return &RecentDelegation{
			FinishedAt:           run.FinishedAt,
			ExitCode:             run.ExitCode,
			TimedOut:             run.TimedOut,
			HadPermissionDenials: run.HadPermissionDenials,
			LogPath:              runFilePath,
		}, nil
	}

	return nil, nil
}

func readDelegationRun(runFilePath string) (delegationRun, error) {
	content, err := os.ReadFile(runFilePath)
	if err != nil {
		return delegationRun{}, err
	}

	var run delegationRun
	if err := json.Unmarshal(content, &run); err != nil {
		return delegationRun{}, err
	}

	// 모르는 판의 값을 아는 판인 척 읽지 않는다. 판이 오르는 것은 필드를 빼거나 뜻을 바꿀 때다.
	if run.SchemaVersion != delegationRunFileSchemaVersion {
		return delegationRun{}, fmt.Errorf("모르는 판의 위임 기록입니다 (%s): schemaVersion %d", runFilePath, run.SchemaVersion)
	}
	return run, nil
}
