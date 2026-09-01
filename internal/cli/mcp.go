package cli

import (
	"fmt"
	"io"
	"path/filepath"
	"runtime/debug"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/mcpserver"
)

// 이 파일은 kyu mcp 다 — 메인 세션의 모델에게 이 엔진의 도구를 보이는 자리.
//
// 세션을 여는 다른 명령과 달리 이 명령은 답하고 끝나지 않는다. stdin 이 닫힐 때까지 살아 있고,
// 그 수명은 자기를 띄운 메인 세션의 수명이다 — claude 가 세션을 접으면 stdin 이 닫히고 이 서버도
// 함께 끝난다. 엔진이 프로세스를 관리하지 않는다는 자세(설계 문서 5.1)가 여기서도 성립한다.

const mcpUsageText = `사용법: kyu mcp serve <워크디렉토리>

  kyu mcp serve <워크디렉토리>   메인 세션에 붙는 오케스트레이션 MCP 서버를 stdio 로 연다

serve 는 사람이 직접 부르는 명령이 아니다. kyu session-command --json 이 답하는 메인 세션 명령의
--mcp-config 에 이 명령이 실려 있고, claude 가 그것을 자식 프로세스로 띄운다.`

// ManageOrchestrationTools 는 kyu mcp 를 실행한다 — 도구를 여는 serve 와 레포를 승인하는 approve.
//
// 두 하위 명령이 한 낱말 아래 있는 이유는 둘이 같은 것을 다뤄서다. serve 가 여는 위임 통로에
// 관문을 세우는 것이 approve 이고(설계 문서 5.6), 그 관문의 기록도 같은 .coord 에 남는다.
func ManageOrchestrationTools(in io.Reader, out, errOut io.Writer, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("mcp 는 하위 명령이 필요합니다\n\n%s", mcpUsageText)
	}

	subcommandName, subcommandArgs := args[0], args[1:]

	switch subcommandName {
	case mcpServeSubcommandName:
		return serveOrchestrationTools(in, out, subcommandArgs)

	default:
		return fmt.Errorf("mcp 가 모르는 하위 명령입니다: %s\n\n%s", subcommandName, mcpUsageText)
	}
}

// serveOrchestrationTools 는 이 워크디렉토리의 도구를 stdio 로 연다.
//
// 워크디렉토리를 인자로 받는다. 실행한 디렉토리를 쓰지 않는 이유는 이 프로세스의 cwd 가 우리가
// 정한 것이 아니어서다 — claude 가 자식으로 띄우므로 그 cwd 는 메인 세션의 것이고, 지금은 그것이
// 워크디렉토리와 같지만 그 사실에 기대면 claude 가 자식의 cwd 를 바꾸는 날 조용히 어긋난다.
func serveOrchestrationTools(in io.Reader, out io.Writer, args []string) error {
	workDirPath, err := parseServeArgs(args)
	if err != nil {
		return err
	}

	// 절대경로로 굳힌다. 이 값은 도구가 답하는 문서에 그대로 실리고 위임의 cwd 가 되므로,
	// 상대경로가 한 번이라도 섞이면 어느 디렉토리에서 이 서버가 떴는지에 따라 답이 달라진다.
	absoluteWorkDirPath, err := filepath.Abs(workDirPath)
	if err != nil {
		return fmt.Errorf("워크디렉토리 절대경로 변환 실패 (%s): %w", workDirPath, err)
	}

	buildInfo, _ := debug.ReadBuildInfo()

	return mcpserver.Serve(in, out, orchestrationServerName, versionName(buildInfo), orchestrationTools(absoluteWorkDirPath))
}

// orchestrationTools 는 메인 세션에 보일 도구 전부다(설계 문서 5.3).
//
// plan 읽기·쓰기 도구를 두지 않는다. 메인 세션의 cwd 가 워크디렉토리라 .coord/plan.md 는 내장
// Read·Edit 도구로 이미 닿고, 같은 파일에 이르는 길이 둘이 되면 둘이 다르게 굴 자리가 생긴다
// (설계 문서 5.3.1).
func orchestrationTools(workDirPath string) []mcpserver.Tool {
	return []mcpserver.Tool{
		newListReposTool(workDirPath),
	}
}

func parseServeArgs(args []string) (string, error) {
	var workDirPath string

	for _, arg := range args {
		switch {
		// 모르는 옵션을 경로로 흘려보내면 "워크디렉토리 읽기 실패 (--typo)" 라는 엉뚱한 안내가 나온다.
		case strings.HasPrefix(arg, "-"):
			return "", fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, mcpUsageText)

		case workDirPath != "":
			return "", fmt.Errorf("mcp serve 는 워크디렉토리 하나만 받습니다 (인자 %d 개를 받음)\n\n%s", len(args), mcpUsageText)

		default:
			workDirPath = arg
		}
	}

	if workDirPath == "" {
		return "", fmt.Errorf("mcp serve 는 어느 워크디렉토리의 도구를 열지 알아야 합니다\n\n%s", mcpUsageText)
	}
	return workDirPath, nil
}
