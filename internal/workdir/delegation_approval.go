package workdir

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// 이 파일은 위임의 관문이다 — orchestration-tools-design.md 5.6.
//
// 왜 관문이 필요한가: claude 의 승인 관문은 대화형에만 있고 헤드리스에는 없다(설계 문서 3.2).
// 위임은 헤드리스다. 그래서 이 도구는 그 관문을 우회하는 길을 새로 내는데, 레포가 제공하는 설정
// 파일 중에는 **위임을 걸기만 하면 모델의 도구 호출 없이 명령을 실행하는 것**이 있어서 kyu clone
// 으로 레포를 받고 메인이 위임하는 것만으로 사람이 한 번도 본 적 없는 명령이 돈다.
//
// **우회로를 내는 쪽이 관문도 함께 낸다.** 대화형이 하는 일을 이 도구가 헤드리스에 대해 대신한다.
//
// 다른 기록 파일과 자세가 반대인 자리가 하나 있다. 계획과 대화 기록은 읽지 못하면 버리고 계속
// 가지만, 이 기록은 읽지 못하면 "승인 없음" 으로 본다 — 버리고 계속 가면 관문이 파일 하나
// 깨뜨리는 것으로 열린다.

// repoExecutionConfigPaths 는 위임을 걸기만 하면 claude 가 사람 확인 없이 실행에 쓰는, 레포가
// 제공하는 설정 파일이다. 2026-09-01 실측이 이 목록을 정했다(설계 문서 5.6 의 표).
//
// **파일 단위로 본다 — 안의 필드를 읽고 위험한지 가리지 않는다.** 같은 settings.json 안에서
// hooks 와 apiKeyHelper 가 서로 다른 방식으로 돌았고(실측), 그런 필드는 claude 가 판을 올릴
// 때마다 는다. 필드 목록을 우리가 들고 있으면 다음 판에 하나 늘 때마다 관문이 조용히 뚫린다.
//
// 목록에 없는 것과 그 이유도 5.6 에 적어두었다 — .claude/agents/ 와 .claude/commands/ 는 모델이
// 부를 때만 돌고(실측), CLAUDE.md 는 로드되지만 실행이 아니다.
var repoExecutionConfigPaths = []string{
	".mcp.json",
	".claude/settings.json",
	".claude/settings.local.json",
}

// delegationApprovalsFileName 은 승인 기록 파일의 이름이다. 자리는 계획·대화 기록과 같은 .coord 다.
//
// 레포 안에는 넣지 않는다(설계 원칙 2). 팀 공용 레포에 우리 도구의 승인 기록이 남으면, 그 레포를
// 받은 다른 사람이 자기가 승인한 적 없는 것을 승인된 것으로 보게 된다.
const delegationApprovalsFileName = "delegation-approvals.json"

// delegationApprovalsFileSchemaVersion 은 이 파일 형식의 판이다.
const delegationApprovalsFileSchemaVersion = 1

// delegationApprovalsFilePermission 은 승인 기록 파일의 권한이다. 사람이 열어 확인할 수 있어야 한다.
const delegationApprovalsFilePermission = 0o644

// RepoExecutionConfig 는 레포가 제공하는 실행 유발 설정 파일 하나다.
type RepoExecutionConfig struct {
	// RelativePath 는 레포 안에서의 자리다(`.claude/settings.json` 처럼 / 로 적는다).
	// 어느 파일이 걸렸는지를 사람과 모델에게 말할 때 쓴다.
	RelativePath string

	// AbsolutePath 는 사람이 직접 열어 볼 수 있는 자리다.
	AbsolutePath string

	// Content 는 그 파일의 내용이다. 무엇을 승인하는지 모르는 승인은 관문이 아니다.
	Content string
}

// RepoDelegationApproval 은 한 레포에 위임을 걸어도 되는지에 대한 판정이다.
type RepoDelegationApproval struct {
	// ExecutionConfigs 는 이 레포에 실제로 있는 실행 유발 설정 파일들이다. 비어 있으면 물을 것이
	// 없고, 그것이 대부분의 레포다.
	ExecutionConfigs []RepoExecutionConfig

	// Approved 는 지금 그 파일들의 내용이 승인되어 있는지다.
	Approved bool

	// Fingerprint 는 그 파일들 전체의 지문이다. 승인은 레포가 아니라 이 지문에 대한 것이다 —
	// 파일이 바뀌거나 하나 늘면 사람이 본 적 없는 command 가 승인된 레포라는 이름으로 돌게 된다.
	Fingerprint string
}

// DelegationIsAllowed 는 이 레포에 지금 위임을 걸어도 되는지다.
func (approval RepoDelegationApproval) DelegationIsAllowed() bool {
	return len(approval.ExecutionConfigs) == 0 || approval.Approved
}

// InspectRepoDelegationApproval 은 이 레포의 관문 상태를 본다.
func InspectRepoDelegationApproval(workDirPath string, repo Repo) (RepoDelegationApproval, error) {
	executionConfigs, err := readRepoExecutionConfigs(repo)
	if err != nil {
		return RepoDelegationApproval{}, err
	}
	if len(executionConfigs) == 0 {
		// 이 레포는 위임만으로 도는 설정을 갖고 있지 않다. 한 번도 묻지 않는다.
		return RepoDelegationApproval{}, nil
	}

	fingerprint := executionConfigsFingerprint(executionConfigs)

	approvals, err := readDelegationApprovals(workDirPath)
	if err != nil {
		return RepoDelegationApproval{}, err
	}

	return RepoDelegationApproval{
		ExecutionConfigs: executionConfigs,
		Approved:         approvals[repo.Name] == fingerprint,
		Fingerprint:      fingerprint,
	}, nil
}

// ApproveRepoDelegation 은 이 레포의 이 내용을 승인으로 적는다.
//
// 지문을 인자로 받는다. 여기서 파일을 다시 읽으면 사람이 본 내용과 승인되는 내용이 갈릴 수 있다 —
// 화면에 보인 뒤 사람이 답하기까지 사이에 파일이 바뀌면, 사람이 본 적 없는 것이 승인된다.
func ApproveRepoDelegation(workDirPath, repoName, fingerprint string) error {
	approvals, err := readDelegationApprovals(workDirPath)
	if err != nil {
		return err
	}

	approvals[repoName] = fingerprint
	return writeDelegationApprovals(workDirPath, approvals)
}

// readRepoExecutionConfigs 는 이 레포에 실제로 있는 실행 유발 설정을 목록 순서대로 읽는다.
func readRepoExecutionConfigs(repo Repo) ([]RepoExecutionConfig, error) {
	var executionConfigs []RepoExecutionConfig

	for _, relativePath := range repoExecutionConfigPaths {
		absolutePath := filepath.Join(repo.AbsolutePath, filepath.FromSlash(relativePath))

		content, err := os.ReadFile(absolutePath)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			// 파일이 거기 있는데 읽지 못한 것은 없는 것과 다른 사실이다. 없는 것으로 뭉뚱그리면
			// 읽지 못한 설정을 가진 레포가 관문 없이 위임된다.
			return nil, fmt.Errorf("레포의 설정 파일 읽기 실패 (%s): %w", absolutePath, err)
		}

		executionConfigs = append(executionConfigs, RepoExecutionConfig{
			RelativePath: relativePath,
			AbsolutePath: absolutePath,
			Content:      string(content),
		})
	}

	return executionConfigs, nil
}

// executionConfigsFingerprint 는 걸린 파일들 **전체**의 지문을 만든다.
//
// 파일마다 따로 두지 않고 하나로 묶는 이유는 승인의 뜻이다. 사람이 승인하는 것은 "이 레포에서
// 위임 시작만으로 도는 것을 다 봤다" 이므로, 파일이 하나 늘거나 사라지는 것도 그 뜻을 바꾼다.
//
// 자리와 길이를 내용 앞에 적는다. 내용만 이어 붙이면 파일 사이의 경계가 지문에 남지 않아,
// .mcp.json 의 뒷부분을 settings.json 앞으로 옮긴 다른 레포가 같은 지문을 갖는다.
//
// 파일을 통째로 비교하지 않고 지문으로 두는 이유는 기록 파일이 사람이 열어보는 것이어서다 —
// 레포마다 설정 전문이 들어가면 승인이 몇 개인지도 눈으로 세지 못한다.
func executionConfigsFingerprint(executionConfigs []RepoExecutionConfig) string {
	var canonical strings.Builder
	for _, config := range executionConfigs {
		fmt.Fprintf(&canonical, "%s\n%d\n%s", config.RelativePath, len(config.Content), config.Content)
	}

	digest := sha256.Sum256([]byte(canonical.String()))
	return "sha256:" + hex.EncodeToString(digest[:])
}

func delegationApprovalsFilePath(workDirPath string) string {
	return filepath.Join(workDirPath, coordDirectoryName, delegationApprovalsFileName)
}

// delegationApprovalsFile 은 승인 기록의 직렬화 모양이다.
type delegationApprovalsFile struct {
	SchemaVersion int `json:"schemaVersion"`

	// Approvals 는 레포 이름에서 승인된 설정 파일 집합의 지문으로 가는 표다.
	Approvals map[string]string `json:"approvals"`
}

// readDelegationApprovals 는 적혀 있는 승인을 읽는다. 읽지 못하면 승인이 없는 것으로 본다.
//
// 계획·대화 기록은 읽지 못한 것을 경고와 함께 버리고 계속 가지만, 여기서는 경고도 내지 않고
// 그냥 "없음" 이다. 잃는 것이 다르기 때문이다 — 저쪽에서 잃는 것은 이어갈 대화 하나이고,
// 여기서 잘못 읽으면 사람이 본 적 없는 명령이 돈다. 다시 묻는 것이 그 대가다.
func readDelegationApprovals(workDirPath string) (map[string]string, error) {
	recordFilePath := delegationApprovalsFilePath(workDirPath)

	recordFileContent, err := os.ReadFile(recordFilePath)
	if errors.Is(err, os.ErrNotExist) {
		return map[string]string{}, nil
	}
	if err != nil {
		// 파일이 거기 있는데 읽지 못한 것(권한 등)은 승인 없음과 다른 사실이다. 다시 묻는 화면을
		// 아무리 지나도 통과하지 못하게 되므로, 그 이유를 사용자에게 그대로 전한다.
		return nil, fmt.Errorf("위임 승인 기록 읽기 실패 (%s): %w", recordFilePath, err)
	}

	var record delegationApprovalsFile
	if err := json.Unmarshal(recordFileContent, &record); err != nil {
		return map[string]string{}, nil
	}
	if record.SchemaVersion != delegationApprovalsFileSchemaVersion || record.Approvals == nil {
		return map[string]string{}, nil
	}
	return record.Approvals, nil
}

// writeDelegationApprovals 는 승인 표를 기록 파일에 적는다.
//
// 대화 기록과 달리 임시 파일을 거치지 않는다. 도중에 죽어 잘린 문서가 남으면 다음 읽기가 그것을
// "승인 없음" 으로 보고 다시 묻는데, 그것이 이 파일에서 바라는 결과다 — 잘린 승인이 승인으로
// 읽힐 길이 없으므로 원자성을 위한 장치가 필요하지 않다.
func writeDelegationApprovals(workDirPath string, approvals map[string]string) error {
	recordFilePath := delegationApprovalsFilePath(workDirPath)

	coordDirectoryPath := filepath.Dir(recordFilePath)
	if err := os.MkdirAll(coordDirectoryPath, CoordDirectoryPermission); err != nil {
		return fmt.Errorf("조율 디렉토리 생성 실패 (%s): %w", coordDirectoryPath, err)
	}

	document, err := json.MarshalIndent(delegationApprovalsFile{
		SchemaVersion: delegationApprovalsFileSchemaVersion,
		Approvals:     approvals,
	}, "", "  ")
	if err != nil {
		return fmt.Errorf("위임 승인 기록 조립 실패: %w", err)
	}
	document = append(document, '\n')

	if err := os.WriteFile(recordFilePath, document, delegationApprovalsFilePermission); err != nil {
		return fmt.Errorf("위임 승인 기록 쓰기 실패 (%s): %w", recordFilePath, err)
	}
	return nil
}
