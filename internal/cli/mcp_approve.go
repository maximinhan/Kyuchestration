package cli

import (
	"errors"
	"fmt"
	"io"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 kyu mcp approve 다 — 레포의 .mcp.json 을 사람에게 보이고 승인을 받는 자리(설계 문서 5.6).
//
// 이 명령이 따로 있는 이유는 위임에 사람이 없기 때문이다. 위임은 헤드리스라 승인 프롬프트에 답할
// 상대가 없고(설계 문서 2 절), run_in_repo 는 거절하면서 "사람에게 이것을 실행해 달라고 전하라" 고
// 답한다. 그 사람이 실행하는 것이 이 명령이다.
//
// **이 관문이 막는 것과 막지 못하는 것을 정확히 적어둔다.**
//
// 막는 것: 설계 5.6 이 이름 붙인 위협 — *검토되지 않은 레포의 .mcp.json 이 사람에게 한 번도
// 보이지 않은 채 도는 것*. 정상 흐름에서 그 파일은 반드시 사람의 화면을 한 번 지난다.
//
// 막지 못하는 것: **작정하고 우회하는 메인 세션.** 그 프로세스는 우리와 같은 OS 사용자로 돌고,
// 워크디렉토리에 쓰기 권한이 있으며(승인 기록이 그 안에 산다), pty 를 붙이는 도구도 쓸 수 있다.
// 아래의 터미널 요구는 파이프로 답이 흘러드는 것을 막을 뿐, pty 를 붙인 프로그램과 사람을
// 가르지 못한다 — 그것을 가르는 길은 이 계층에 없다. 그 자리의 실제 경계는 메인 세션 자신의
// 도구 권한 확인이고, 그것은 claude 가 기대는 경계와 같은 것이다.
//
// 그래서 이 관문은 **사고를 막는 장치이지 샌드박스가 아니다.** 작정한 우회를 막으려 들면
// 이 계층이 이길 수 없는 경주에 들어간다.

// mcpApproveSubcommandName 은 이 명령의 하위 명령 이름이다.
const mcpApproveSubcommandName = "approve"

// approvalAnswerYes 는 승인으로 읽는 유일한 답이다.
//
// 예/네/y 를 모두 받지 않는다. 무엇이든 승인으로 읽히는 답이 늘어날수록 실수로 지나칠 자리가
// 늘어나고, 이 물음은 실수로 지나가면 사람이 본 적 없는 명령이 도는 자리다.
const approvalAnswerYes = "y"

// approveRepoMCPConfig 는 그 레포의 .mcp.json 을 사람에게 보이고 승인을 받아 적는다.
//
// 대상 워크디렉토리는 명령을 실행한 디렉토리다 — 다른 명령과 같다. 사용자는 앱이 연 워크디렉토리
// 창에서 이 명령을 치게 된다.
func approveRepoMCPConfig(in io.Reader, out io.Writer, args []string) error {
	repoName, err := parseApproveArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	repos, err := workdir.ScanRepos(location.absolutePath)
	if err != nil {
		return err
	}
	repo, err := repoNamed(repos, repoName)
	if err != nil {
		return err
	}

	approval, err := workdir.InspectRepoMCPApproval(location.absolutePath, repo)
	if err != nil {
		return err
	}

	// 물을 것이 없는 것은 실패가 아니다. 승인하러 온 사용자에게 "이 레포는 그냥 위임된다" 를
	// 알리고 끝낸다 — 종료 코드 1 로 끝내면 스크립트가 그것을 실패로 읽는다.
	if !approval.HasMCPConfig {
		_, err := fmt.Fprintf(out, "%s 에는 %s 이 없어 승인할 것이 없습니다 — 이 레포에는 그냥 위임됩니다.\n",
			repo.Name, ".mcp.json")
		return err
	}
	if approval.Approved {
		_, err := fmt.Fprintf(out, "%s 의 지금 %s 내용은 이미 승인되어 있습니다 (%s).\n",
			repo.Name, ".mcp.json", approval.ConfigPath)
		return err
	}

	approved, err := askAboutRepoMCPConfig(in, out, repo.Name, approval)
	if err != nil {
		return err
	}
	if !approved {
		_, err := fmt.Fprintf(out, "승인하지 않았습니다 — %s 에는 위임되지 않습니다.\n", repo.Name)
		return err
	}

	if err := workdir.ApproveRepoMCPConfig(location.absolutePath, repo.Name, approval.Fingerprint); err != nil {
		return err
	}

	_, err = fmt.Fprintf(out, "승인했습니다 — 이제 %s 에 위임할 수 있습니다. %s 이 바뀌면 다시 묻습니다.\n",
		repo.Name, ".mcp.json")
	return err
}

// askAboutRepoMCPConfig 는 파일 내용을 보이고 한 줄로 답을 받는다.
func askAboutRepoMCPConfig(in io.Reader, out io.Writer, repoName string, approval workdir.RepoMCPApproval) (bool, error) {
	prompt := newInteractivePrompt(in, out)

	// 파이프 너머에는 이 물음에 답할 상대가 없다. 그런데도 읽어버리면 승인이 사람을 한 번도
	// 지나지 않고 저장된다.
	//
	// 이것이 가르는 것은 파이프와 터미널까지다. pty 를 붙인 프로그램(script · unbuffer 등)은
	// 여기서 사람과 구분되지 않는다 — 구분하는 길이 이 계층에 없다. 이 줄은 사고를 막고,
	// 작정한 우회의 경계는 메인 세션 자신의 도구 권한 확인이 진다(이 파일 머리말).
	if !prompt.readsFromATerminal() {
		return false, fmt.Errorf(
			"MCP 설정 승인은 사람이 직접 확인하는 자리라 터미널에서만 할 수 있습니다 — %s 에서 터미널을 열고 kyu mcp %s %s 를 실행하세요",
			repoName, mcpApproveSubcommandName, repoName)
	}

	if _, err := fmt.Fprintf(out, `%s 의 %s 입니다. 이 파일에 적힌 command 는 이 레포에 위임하는 순간 그대로 실행됩니다.

%s
%s
`, repoName, ".mcp.json", approval.ConfigPath, approval.ConfigContent); err != nil {
		return false, fmt.Errorf("MCP 설정 출력 실패: %w", err)
	}

	if _, err := fmt.Fprintf(out, "이 내용으로 %s 에 위임하는 것을 승인할까요? (%s/N): ", repoName, approvalAnswerYes); err != nil {
		return false, fmt.Errorf("물음 출력 실패: %w", err)
	}

	answer, err := prompt.readAnswerLine()
	if errors.Is(err, errInputClosed) {
		// 답하지 않고 끝낸 것은 거절이다. 승인이 아닌 모든 것은 승인이 아니다.
		return false, nil
	}
	if err != nil {
		return false, err
	}

	return strings.EqualFold(answer, approvalAnswerYes), nil
}

func parseApproveArgs(args []string) (string, error) {
	var repoName string

	for _, arg := range args {
		switch {
		case strings.HasPrefix(arg, "-"):
			return "", fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, mcpUsageText)

		case repoName != "":
			return "", fmt.Errorf("mcp approve 는 레포 이름 하나만 받습니다 (인자 %d 개를 받음)\n\n%s", len(args), mcpUsageText)

		default:
			repoName = arg
		}
	}

	if repoName == "" {
		return "", fmt.Errorf("mcp approve 는 어느 레포를 승인할지 알아야 합니다\n\n%s", mcpUsageText)
	}
	return repoName, nil
}
