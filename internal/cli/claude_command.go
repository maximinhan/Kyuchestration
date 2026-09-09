package cli

import (
	"encoding/json"
	"fmt"
	"os"
	"slices"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 세션이 실행할 것을 조립하는 자리다 — argv 와, 바탕 환경에 더할 항목.
//
// 조립 지식이 엔진에 남는 이유가 이 파일의 존재 이유다(설계 문서 5.2.1 가). 세션을 여는 쪽은
// 이제 앱뿐이지만, --add-dir 목록과 옵션이 claude 의 어느 플래그·환경변수로 옮겨지는지를 앱이
// 따로 알기 시작하면 그 지식이 두 곳에 놓인다. 갈라진 것을 발견하는 자리는 "메인 세션이 레포
// 지침을 안 읽는다" 이고, 그때 어느 쪽이 틀렸는지 알아내는 데 오래 걸린다.

// sessionCommandName 은 세션 안에서 실행할 명령이다.
//
// v1 은 하드코딩한다. 설정으로 빼려면 어디에 두고 어떤 형식으로 읽고 인자를 어떻게 쪼갤지까지
// 정해야 하는데, 지금 이 도구를 쓰는 사람이 세션에서 띄우는 명령은 하나뿐이다.
// 두 번째 명령이 실제로 필요해지면 그때의 요구를 보고 정한다.
const sessionCommandName = "claude"

// addDirFlagName 은 메인 세션에 하위 레포를 파일 접근으로 붙이는 플래그다(설계 문서 5.4).
const addDirFlagName = "--add-dir"

// repoClaudeMdOptionName 은 메인 세션이 각 레포의 지침 파일까지 읽게 하는 옵션이다.
const repoClaudeMdOptionName = "--repo-claude-md"

// bypassPermissionsOptionName 은 세션의 claude 를 권한 확인 없이 띄우는 옵션이다.
//
// claude 의 원래 플래그 이름을 그대로 노출하지 않는다. 이 도구의 사용자가 정하는 것은
// "이 세션에서 권한 확인을 생략한다" 이고, 그것을 claude 의 어느 플래그로 옮길지는 이 도구의 사정이다.
const bypassPermissionsOptionName = "--bypass-permissions"

// skipPermissionsFlagName 은 위 옵션이 claude 에게 전달되는 플래그다.
const skipPermissionsFlagName = "--dangerously-skip-permissions"

// chatModeOptionName 은 앱이 세션을 PTY 가 아니라 파이프로 부릴 때 붙이는 옵션이다.
//
// 답 문서의 모양은 이 옵션으로 바뀌지 않는다(chat-ui-design.md 5.2.1). 바뀌는 것은 조립이고,
// 앱은 전과 똑같이 command 를 그대로 실행한다 — 파이프에 물릴지 PTY 에 물릴지는 물은 쪽이 이미 안다.
const chatModeOptionName = "--chat"

// chatModeFlags 는 claude 를 사람이 보는 화면이 아니라 프로그램으로 부리는 플래그다.
//
// 값이 아니라 함수인 이유는 이 목록이 아무 데서나 원소를 바꿀 수 있는 패키지 전역 슬라이스가
// 되지 않게 하려는 것이다. 이 순서와 철자는 claude 와 맺는 계약이라 고쳐 쓸 자리를 두지 않는다.
func chatModeFlags() []string {
	return []string{
		// 한 프로세스가 대화를 통째로 산다(chat-ui-design.md 3.1). 매번 새로 띄우는 것이 아니라
		// stdin 에 사용자 메시지 한 줄을 쓸 때마다 한 턴이 돌고, result 한 줄로 끝난다.
		"-p",
		"--input-format", "stream-json",
		"--output-format", "stream-json",
		// stream-json 출력은 --verbose 와 함께 쓴다 — 실측이 이 조합으로 쟀다(3.1).
		"--verbose",
		// 글자 단위 조각(stream_event)을 켠다(3.3). 완성본은 뒤이어 assistant 이벤트로 다시 오므로,
		// 조각은 화면을 부드럽게 하는 데만 쓰이고 전사의 사실은 완성본이다.
		"--include-partial-messages",
		// 앱이 보낸 사용자 메시지를 user 이벤트로 되돌려받는다(3.14). 전사가 한 물줄기에서만
		// 오게 하는 것이 원칙 14 다 — 앱이 자기가 아는 것을 스트림에 섞어 넣으면 큐잉(3.11)이나
		// 중단이 끼는 순간 순서가 어긋나고, 그 어긋남은 사용자가 스크롤을 올릴 때 드러난다.
		"--replay-user-messages",
		// 서브에이전트가 한 말을 안쪽 대화로 받는다(3.10).
		//
		// **이 플래그가 무엇을 더하는지 6 단계가 다시 쟀다**(2026-09-08, claude 2.1.259). 이것이
		// 없어도 서브에이전트의 도구 호출과 프롬프트는 parent_tool_use_id 가 채워진 채로 오지만,
		// 그 에이전트가 한 **말과 사고 블록은 오지 않는다** — 같은 프롬프트를 두 번 돌려 갈랐다.
		// 안쪽 대화를 접어 담는 카드(6 단계)가 그 말을 담을 자리라서 여기서 켠다.
		"--forward-subagent-text",
	}
}

// mcpConfigFlagName 은 claude 에게 MCP 서버 설정을 직접 넘기는 플래그다.
//
// 파일이 아니라 JSON 문자열을 준다. 워크디렉토리 .mcp.json 에 적는 길은 대화형 승인 관문을 타고
// (설계 문서 3.2), 미리 허용하는 설정은 듣지 않았다(3.8). 문자열은 그 관문을 타지 않는다.
const mcpConfigFlagName = "--mcp-config"

// permissionPromptToolFlagName 은 승인이 필요한 호출마다 부를 MCP 도구를 가리키는 플래그다.
//
// 이 플래그가 붙는 순간 그 도구는 모델의 도구 목록에서 사라진다(실측 3.5 · A.7) — 모델이 자기
// 승인 도구를 스스로 불러 통과하는 길이 애초에 없다.
const permissionPromptToolFlagName = "--permission-prompt-tool"

// orchestrationServerName 은 메인 세션이 이 엔진의 도구를 부를 때 쓰는 서버 이름이다.
//
// 모델이 보는 도구 이름이 mcp__kyu__run_in_repo 가 된다. 짧게 두는 이유가 그것이다.
const orchestrationServerName = "kyu"

// permissionAskServerName 은 승인 물음을 앱에게 중계하는 서버의 이름이다(chat-ui-design.md 5.2.2).
//
// **하이픈이 도구 이름에 그대로 남는다.** kyu-ask 로 붙인 서버의 도구가 mcp__kyu-ask__… 으로
// 보였다(실측 A.15) — 콜론이 밑줄로 바뀌는 것(plugin:context7:context7)과 다르다. 이것을 재보지
// 않고 밑줄로 적었으면 승인 도구가 한 번도 안 불렸을 자리다.
//
// 오케스트레이션 서버(kyu)와 이름이 갈려 있어야 하는 이유는 claude 가 서버 이름으로 도구를
// 가리키기 때문이다. 한 서버에 합치면 --permission-prompt-tool 이 그 서버의 도구 하나만 가리는
// 사이 run_in_repo 는 그대로 모델에게 보이는데, 그 둘의 수명과 등록 대상 세션이 서로 다르다
// (오케스트레이션은 메인 세션에만 · 승인은 둘 다).
const permissionAskServerName = "kyu-ask"

// mcpCommandName 과 mcpServeSubcommandName 은 등록된 서버가 실행할 이 바이너리의 명령이다.
//
// 설계 문서 9 절 8 번이 "mcp 라는 낱말 아래 있을 것이 서버 하나뿐이라 지금은 kyu mcp 로 둔다.
// 두 번째가 생기면 kyu mcp serve 로 옮긴다" 라고 적었다. 승인 명령(kyu mcp approve — 설계 5.6)이
// 그 두 번째라 지금 옮겨 둔다.
const (
	mcpCommandName         = "mcp"
	mcpServeSubcommandName = "serve"
)

// bypassPermissionsEnvName 과 bypassPermissionsEnvValue 는 메인 세션이 bypass 로 열렸다는 표식이다.
//
// 위임은 메인보다 넓은 권한을 갖지 않는다(설계 원칙 13). 그 사실이 위임까지 닿는 통로가 이
// 환경변수다 — stdio MCP 서버는 자기를 띄운 claude 의 환경을 물려받으므로, kyu mcp serve 가
// 이것을 읽고 위임에 --dangerously-skip-permissions 를 붙인다(설계 문서 5.4.1).
//
// 명령이 아니라 환경으로 나르는 이유는 받는 쪽이 claude 가 아니라 우리 서버여서다. claude 의
// 플래그로 넣으면 메인 세션 자신의 권한이 바뀌고, 그것은 이 표식이 나르려는 사실이 아니다.
const (
	bypassPermissionsEnvName  = "KYU_BYPASS_PERMISSIONS"
	bypassPermissionsEnvValue = "1"
)

// repoClaudeMdEnvName 과 repoClaudeMdEnvValue 는 추가 디렉토리의 CLAUDE.md 와 .claude/rules/ 를
// 로드시키는 환경변수다.
//
// 기본으로 켜지 않는다. 붙인 레포 수만큼 컨텍스트가 늘어나므로, 계획이 레포 컨벤션과 어긋나는
// 문제가 실제로 생겼을 때만 켜는 것이 설계의 판단이다(설계 문서 5.4).
//
// 이름과 값을 갈라 둔다. 답 문서의 env 가 객체라 이름과 값을 따로 넣어야 하는데, 붙여둔
// 한 문자열로 두면 여기서 = 를 다시 쪼개야 하고 그 쪼개기가 이름에 = 가 없다는 전제를 또 만든다.
const (
	repoClaudeMdEnvName  = "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"
	repoClaudeMdEnvValue = "1"
)

// mainSessionCommand 는 메인 세션이 실행할 명령을 조립한다: claude --add-dir <레포 절대경로>...
//
// 레포 목록을 사용자가 관리하는 설정에서 읽지 않고 스캔 결과로 만든다. 클론하면 곧바로 붙는 것이
// 요구사항이고(설계 문서 5.3), 손으로 유지하는 목록은 클론 뒤 한 번 잊는 순간 조용히 어긋난다.
//
// 절대경로로 넘기는 이유: 세션의 cwd 가 워크디렉토리라 상대경로도 당장은 맞지만, 서로 다른
// 워크디렉토리에 같은 이름의 레포가 있을 수 있어 이름과 상대경로는 레포를 특정하지 못한다(설계 문서 11절 4번).
// 오케스트레이션 서버 등록도 여기서 붙는다. 그것이 메인 세션에만 붙는 근거는 설계 문서 5.2.1 이다 —
// 레포 세션이 run_in_repo 를 가지면 레포 세션이 다른 레포에 위임하게 되고, 위임의 시작점은 메인 하나다.
func mainSessionCommand(
	repos []workdir.Repo,
	request sessionCommandRequest,
	conversationFlags []string,
	mcpSetup sessionMcpSetup,
) []string {
	command := claudeCommand(request, conversationFlags, mcpSetup)

	// 붙일 레포 수만큼 미리 늘린다. 아래 반복이 레포마다 두 자리씩 더하는데,
	// 그때마다 슬라이스가 자라면 레포가 많은 워크디렉토리에서 같은 목록을 여러 번 복사하게 된다.
	command = slices.Grow(command, 2*len(repos))

	for _, repo := range repos {
		command = append(command, addDirFlagName, repo.AbsolutePath)
	}
	return command
}

// sessionMcpSetup 은 이 세션의 명령에 붙는 MCP 관련 자리 둘이다 — 서버 등록 문자열과,
// 그중 승인 물음을 받을 도구를 가리키는 이름.
//
// 둘을 함께 든다. 승인 플래그는 그 도구를 여는 서버가 등록되어 있을 때만 뜻이 있고, 등록 없이
// 플래그만 붙으면 claude 는 없는 도구를 가리킨 채 관문마다 실패한다 — 두 값을 따로 나르면
// 한쪽만 붙는 조립이 언제든 가능해진다.
type sessionMcpSetup struct {
	// mcpConfig 는 --mcp-config 에 실릴 문자열이다. 붙일 서버가 없으면 비어 있다.
	mcpConfig string

	// permissionPromptTool 은 --permission-prompt-tool 에 실릴 도구 이름이다.
	// 승인 브리지가 없으면 비어 있다.
	permissionPromptTool string
}

// mainSessionMcpSetup 은 메인 세션에 붙일 서버들을 등록으로 옮긴다 — 오케스트레이션과, 있다면 승인.
func mainSessionMcpSetup(workDirAbsolutePath string, request sessionCommandRequest) (sessionMcpSetup, error) {
	engineExecutablePath, err := os.Executable()
	if err != nil {
		return sessionMcpSetup{}, fmt.Errorf("이 엔진의 실행 경로를 알아내지 못해 MCP 서버를 등록할 수 없습니다: %w", err)
	}

	servers := map[string]any{
		orchestrationServerName: engineMcpServer(engineExecutablePath, mcpServeSubcommandName, workDirAbsolutePath),
	}
	return mcpSetupFor(servers, engineExecutablePath, request)
}

// repoSessionMcpSetup 은 레포 세션에 붙일 서버를 등록으로 옮긴다 — 있다면 승인 서버 하나뿐이다.
//
// 오케스트레이션 서버는 여기 없다. 레포 세션이 run_in_repo 를 가지면 레포 세션이 다른 레포에
// 위임하게 되고, 위임의 시작점은 메인 하나다(orchestration-tools-design.md 5.2.1). 승인은 그
// 경계와 무관하다 — 위임의 문제가 아니라 화면의 문제라, 사람이 앞에 있는 세션은 둘 다 받는다.
func repoSessionMcpSetup(request sessionCommandRequest) (sessionMcpSetup, error) {
	if !request.wantsPermissionBridge() {
		return sessionMcpSetup{}, nil
	}

	engineExecutablePath, err := os.Executable()
	if err != nil {
		return sessionMcpSetup{}, fmt.Errorf("이 엔진의 실행 경로를 알아내지 못해 승인 서버를 등록할 수 없습니다: %w", err)
	}
	return mcpSetupFor(map[string]any{}, engineExecutablePath, request)
}

// mcpSetupFor 는 받은 서버들에 승인 서버를 더해 --mcp-config 문자열 하나로 만든다.
//
// 담기는 것은 kyu 절대경로와 워크디렉토리·소켓 절대경로뿐이다. 이 문자열은 프로세스 인자로
// 들어가 ps 에 보이므로(orchestration-tools-design.md 5.2 의 대가), 비밀이 생기면 이 자리를
// 다시 정해야 한다 — 소켓 경로가 비밀이 아닌 것은 그 소켓의 권한이 0600 이기 때문이다(5.4).
func mcpSetupFor(servers map[string]any, engineExecutablePath string, request sessionCommandRequest) (sessionMcpSetup, error) {
	var setup sessionMcpSetup

	if request.wantsPermissionBridge() {
		servers[permissionAskServerName] = engineMcpServer(engineExecutablePath, mcpAskSubcommandName, request.approvalSocketPath)
		setup.permissionPromptTool = permissionPromptToolName()
	}

	if len(servers) == 0 {
		return setup, nil
	}

	// 손으로 문자열을 잇지 않는다. 경로에 따옴표나 역슬래시가 들어가는 날 — 윈도우 경로가
	// 그렇다 — 이어붙인 문자열은 claude 가 읽지 못하는 JSON 이 된다.
	registration, err := json.Marshal(map[string]any{"mcpServers": servers})
	if err != nil {
		return sessionMcpSetup{}, fmt.Errorf("MCP 서버 등록 조립 실패: %w", err)
	}

	setup.mcpConfig = string(registration)
	return setup, nil
}

// engineMcpServer 는 이 엔진의 하위 명령 하나를 stdio 서버로 등록하는 항목이다.
//
// 이 엔진이 자기 경로를 적는다(설계 문서 5.2). 설정 파일에 적어두는 길이었다면 바이너리를 옮기거나
// 올리는 순간 그 파일이 조용히 낡는데, 답하는 쪽이 os.Executable() 로 자기를 적으면 앱이
// KYU_BINARY_PATH 로 어느 엔진을 겨누든 그 엔진이 등록된다.
func engineMcpServer(engineExecutablePath, subcommandName, subcommandArgument string) map[string]any {
	return map[string]any{
		"type":    "stdio",
		"command": engineExecutablePath,
		"args":    []string{mcpCommandName, subcommandName, subcommandArgument},
	}
}

// permissionPromptToolName 은 claude 에게 넘길 승인 도구의 이름이다.
//
// **하이픈이 그대로 남는다**(실측 A.15). kyu-ask 로 붙인 서버의 도구는 mcp__kyu-ask__… 으로
// 보였다 — 콜론이 밑줄로 바뀌는 것(plugin:context7:context7)과 다르다. 조립으로 두는 이유는
// 서버 이름과 도구 이름이 각자 자기 자리에 있고, 이 문자열이 그 둘에서 자동으로 따라오게 하려는
// 것이다 — 손으로 적어 두면 한쪽을 고친 날 조용히 어긋난다.
func permissionPromptToolName() string {
	return "mcp__" + permissionAskServerName + "__" + requestPermissionToolName
}

// claudeCommand 는 두 세션이 공통으로 실행하는 부분이다: claude 와, 챗 모드라면 스트림 플래그와,
// 이 세션이 이어갈 대화를 가리키는 플래그와, 켜져 있다면 권한 확인 생략 플래그.
//
// 메인 세션은 뒤에 --add-dir 을 잇고 레포 세션은 여기서 끝난다. 공통부를 한 곳에 두는 이유는
// 두 세션이 같은 옵션에 다르게 반응하면 사용자가 그 차이를 세션 안에서야 발견하기 때문이다.
//
// 요청을 통째로 받는다. 명령에 영향을 주는 옵션이 둘이 되면서, 필드를 하나씩 넘기면 옵션이
// 하나 늘 때마다 두 호출자의 인자 목록이 함께 길어진다 — 그리고 그때 한쪽만 고치면 메인과
// 레포 세션이 같은 옵션에 다르게 반응한다.
func claudeCommand(request sessionCommandRequest, conversationFlags []string, mcpSetup sessionMcpSetup) []string {
	chatFlags := []string(nil)
	if request.chatMode {
		chatFlags = chatModeFlags()
	}

	command := make([]string, 0, 2+len(chatFlags)+len(conversationFlags))
	command = append(command, sessionCommandName)

	// 스트림 플래그가 맨 앞이다. claude 는 순서를 가리지 않지만, 사람이 ps 에서 이 명령을 볼 때
	// "무엇으로 부리는가" 가 먼저 오고 "무엇을 이어가는가" 가 뒤에 오는 편이 읽힌다.
	command = append(command, chatFlags...)

	command = append(command, conversationFlags...)
	if request.bypassPermissions {
		command = append(command, skipPermissionsFlagName)
	}

	// 승인 플래그가 등록보다 앞이다. 설계 5.2 가 적어둔 순서 그대로이고, ps 에서 이 명령을 보는
	// 사람에게 "무엇이 관문을 지는가" 가 "무엇이 붙어 있는가" 보다 먼저 읽힌다.
	if mcpSetup.permissionPromptTool != "" {
		command = append(command, permissionPromptToolFlagName, mcpSetup.permissionPromptTool)
	}
	if mcpSetup.mcpConfig != "" {
		command = append(command, mcpConfigFlagName, mcpSetup.mcpConfig)
	}
	return command
}

// sessionEnvironmentEntry 는 세션이 자기 바탕 환경에 얹어야 하는 환경변수 하나다.
type sessionEnvironmentEntry struct {
	name  string
	value string
}

// sessionEnvironment 는 이번 요청이 세션에 더할 환경변수를 정한다. 더할 것이 없으면 비어 있다.
//
// 순서 있는 목록으로 답한다. Go 의 map 은 순회 순서가 매번 달라서, 환경변수가 둘이 되는 날
// 답 문서의 키 순서가 실행마다 흔들린다 — 앱의 기록에 그대로 남는 값이다.
func sessionEnvironment(request sessionCommandRequest) []sessionEnvironmentEntry {
	var environment []sessionEnvironmentEntry

	if request.loadRepoClaudeMd {
		environment = append(environment, sessionEnvironmentEntry{name: repoClaudeMdEnvName, value: repoClaudeMdEnvValue})
	}

	// bypass 표식은 메인 세션에만 붙는다. 이것을 읽는 상대는 오케스트레이션 서버이고, 그 서버는
	// 메인 세션에만 등록되므로(설계 문서 5.2.1) 레포 세션에 실어 보내면 읽을 상대가 없다.
	if request.bypassPermissions && request.repoName == "" {
		environment = append(environment, sessionEnvironmentEntry{name: bypassPermissionsEnvName, value: bypassPermissionsEnvValue})
	}

	return environment
}
