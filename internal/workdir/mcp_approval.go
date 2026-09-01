package workdir

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// 이 파일은 레포의 .mcp.json 관문이다 — orchestration-tools-design.md 5.6.
//
// 왜 관문이 필요한가: claude 의 .mcp.json 승인 관문은 대화형에만 있고 헤드리스에는 없다
// (설계 문서 3.2). 위임은 헤드리스다. 그래서 이 도구는 그 관문을 우회하는 길을 새로 내는데,
// .mcp.json 은 임의 명령을 실행하는 파일이므로(command · args 필드) kyu clone 으로 레포를 받고
// 메인이 위임하는 것만으로 사람이 한 번도 본 적 없는 명령이 돈다.
//
// **우회로를 내는 쪽이 관문도 함께 낸다.** 대화형이 하는 일을 이 도구가 헤드리스에 대해 대신한다.
//
// 다른 기록 파일과 자세가 반대인 자리가 하나 있다. 계획과 대화 기록은 읽지 못하면 버리고 계속
// 가지만, 이 기록은 읽지 못하면 "승인 없음" 으로 본다 — 버리고 계속 가면 관문이 파일 하나
// 깨뜨리는 것으로 열린다.

// mcpApprovalsFileName 은 승인 기록 파일의 이름이다. 자리는 계획·대화 기록과 같은 .coord 다.
//
// 레포 안에는 넣지 않는다(설계 원칙 2). 팀 공용 레포에 우리 도구의 승인 기록이 남으면, 그 레포를
// 받은 다른 사람이 자기가 승인한 적 없는 것을 승인된 것으로 보게 된다.
const mcpApprovalsFileName = "mcp-approvals.json"

// mcpApprovalsFileSchemaVersion 은 이 파일 형식의 판이다.
const mcpApprovalsFileSchemaVersion = 1

// mcpApprovalsFilePermission 은 승인 기록 파일의 권한이다. 사람이 열어 확인할 수 있어야 한다.
const mcpApprovalsFilePermission = 0o644

// repoMCPConfigFileName 은 레포가 자기 MCP 서버를 적어두는 파일이다.
const repoMCPConfigFileName = ".mcp.json"

// RepoMCPApproval 은 한 레포에 위임을 걸어도 되는지에 대한 판정이다.
type RepoMCPApproval struct {
	// HasMCPConfig 는 이 레포에 .mcp.json 이 있는지다. 없으면 물을 것이 없다.
	HasMCPConfig bool

	// Approved 는 지금 그 파일의 내용이 승인되어 있는지다.
	Approved bool

	// Fingerprint 는 그 파일 내용의 지문이다. 승인은 레포가 아니라 이 지문에 대한 것이다 —
	// 파일이 바뀌면 사람이 본 적 없는 command 가 승인된 레포라는 이름으로 돌게 된다.
	Fingerprint string

	// ConfigPath 와 ConfigContent 는 사람에게 무엇을 승인하는지 보이기 위한 것이다.
	// 무엇을 승인하는지 모르는 승인은 관문이 아니다.
	ConfigPath    string
	ConfigContent string
}

// DelegationIsAllowed 는 이 레포에 지금 위임을 걸어도 되는지다.
func (approval RepoMCPApproval) DelegationIsAllowed() bool {
	return !approval.HasMCPConfig || approval.Approved
}

// InspectRepoMCPApproval 은 이 레포의 관문 상태를 본다.
func InspectRepoMCPApproval(workDirPath string, repo Repo) (RepoMCPApproval, error) {
	configPath := filepath.Join(repo.AbsolutePath, repoMCPConfigFileName)

	configContent, err := os.ReadFile(configPath)
	if errors.Is(err, os.ErrNotExist) {
		// 이 레포는 MCP 서버를 갖고 있지 않다. 대부분의 레포가 이쪽이고, 한 번도 묻지 않는다.
		return RepoMCPApproval{}, nil
	}
	if err != nil {
		// 파일이 거기 있는데 읽지 못한 것은 없는 것과 다른 사실이다. 없는 것으로 뭉뚱그리면
		// 읽지 못한 .mcp.json 을 가진 레포가 관문 없이 위임된다.
		return RepoMCPApproval{}, fmt.Errorf("레포의 MCP 설정 읽기 실패 (%s): %w", configPath, err)
	}

	fingerprint := mcpConfigFingerprint(configContent)

	approvals, err := readMCPApprovals(workDirPath)
	if err != nil {
		return RepoMCPApproval{}, err
	}

	return RepoMCPApproval{
		HasMCPConfig:  true,
		Approved:      approvals[repo.Name] == fingerprint,
		Fingerprint:   fingerprint,
		ConfigPath:    configPath,
		ConfigContent: string(configContent),
	}, nil
}

// ApproveRepoMCPConfig 는 이 레포의 이 내용을 승인으로 적는다.
//
// 지문을 인자로 받는다. 여기서 파일을 다시 읽으면 사람이 본 내용과 승인되는 내용이 갈릴 수 있다 —
// 화면에 보인 뒤 사람이 답하기까지 사이에 파일이 바뀌면, 사람이 본 적 없는 것이 승인된다.
func ApproveRepoMCPConfig(workDirPath, repoName, fingerprint string) error {
	approvals, err := readMCPApprovals(workDirPath)
	if err != nil {
		return err
	}

	approvals[repoName] = fingerprint
	return writeMCPApprovals(workDirPath, approvals)
}

// mcpConfigFingerprint 는 파일 내용의 지문을 만든다.
//
// 파일을 통째로 비교하지 않고 지문으로 두는 이유는 기록 파일이 사람이 열어보는 것이어서다 —
// 레포마다 .mcp.json 전문이 들어가면 승인이 몇 개인지도 눈으로 세지 못한다.
func mcpConfigFingerprint(configContent []byte) string {
	digest := sha256.Sum256(configContent)
	return "sha256:" + hex.EncodeToString(digest[:])
}

func mcpApprovalsFilePath(workDirPath string) string {
	return filepath.Join(workDirPath, coordDirectoryName, mcpApprovalsFileName)
}

// mcpApprovalsFile 은 승인 기록의 직렬화 모양이다.
type mcpApprovalsFile struct {
	SchemaVersion int `json:"schemaVersion"`

	// Approvals 는 레포 이름에서 승인된 .mcp.json 의 지문으로 가는 표다.
	Approvals map[string]string `json:"approvals"`
}

// readMCPApprovals 는 적혀 있는 승인을 읽는다. 읽지 못하면 승인이 없는 것으로 본다.
//
// 계획·대화 기록은 읽지 못한 것을 경고와 함께 버리고 계속 가지만, 여기서는 경고도 내지 않고
// 그냥 "없음" 이다. 잃는 것이 다르기 때문이다 — 저쪽에서 잃는 것은 이어갈 대화 하나이고,
// 여기서 잘못 읽으면 사람이 본 적 없는 명령이 돈다. 다시 묻는 것이 그 대가다.
func readMCPApprovals(workDirPath string) (map[string]string, error) {
	recordFilePath := mcpApprovalsFilePath(workDirPath)

	recordFileContent, err := os.ReadFile(recordFilePath)
	if errors.Is(err, os.ErrNotExist) {
		return map[string]string{}, nil
	}
	if err != nil {
		// 파일이 거기 있는데 읽지 못한 것(권한 등)은 승인 없음과 다른 사실이다. 다시 묻는 화면을
		// 아무리 지나도 통과하지 못하게 되므로, 그 이유를 사용자에게 그대로 전한다.
		return nil, fmt.Errorf("MCP 승인 기록 읽기 실패 (%s): %w", recordFilePath, err)
	}

	var record mcpApprovalsFile
	if err := json.Unmarshal(recordFileContent, &record); err != nil {
		return map[string]string{}, nil
	}
	if record.SchemaVersion != mcpApprovalsFileSchemaVersion || record.Approvals == nil {
		return map[string]string{}, nil
	}
	return record.Approvals, nil
}

// writeMCPApprovals 는 승인 표를 기록 파일에 적는다.
//
// 대화 기록과 달리 임시 파일을 거치지 않는다. 도중에 죽어 잘린 문서가 남으면 다음 읽기가 그것을
// "승인 없음" 으로 보고 다시 묻는데, 그것이 이 파일에서 바라는 결과다 — 잘린 승인이 승인으로
// 읽힐 길이 없으므로 원자성을 위한 장치가 필요하지 않다.
func writeMCPApprovals(workDirPath string, approvals map[string]string) error {
	recordFilePath := mcpApprovalsFilePath(workDirPath)

	coordDirectoryPath := filepath.Dir(recordFilePath)
	if err := os.MkdirAll(coordDirectoryPath, CoordDirectoryPermission); err != nil {
		return fmt.Errorf("조율 디렉토리 생성 실패 (%s): %w", coordDirectoryPath, err)
	}

	document, err := json.MarshalIndent(mcpApprovalsFile{
		SchemaVersion: mcpApprovalsFileSchemaVersion,
		Approvals:     approvals,
	}, "", "  ")
	if err != nil {
		return fmt.Errorf("MCP 승인 기록 조립 실패: %w", err)
	}
	document = append(document, '\n')

	if err := os.WriteFile(recordFilePath, document, mcpApprovalsFilePermission); err != nil {
		return fmt.Errorf("MCP 승인 기록 쓰기 실패 (%s): %w", recordFilePath, err)
	}
	return nil
}
