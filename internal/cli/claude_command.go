package cli

import (
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
func mainSessionCommand(repos []workdir.Repo, request sessionCommandRequest, conversationFlags []string) []string {
	command := claudeCommand(request.bypassPermissions, conversationFlags)

	// 붙일 레포 수만큼 미리 늘린다. 아래 반복이 레포마다 두 자리씩 더하는데,
	// 그때마다 슬라이스가 자라면 레포가 많은 워크디렉토리에서 같은 목록을 여러 번 복사하게 된다.
	command = slices.Grow(command, 2*len(repos))

	for _, repo := range repos {
		command = append(command, addDirFlagName, repo.AbsolutePath)
	}
	return command
}

// claudeCommand 는 두 세션이 공통으로 실행하는 부분이다: claude 와, 이 세션이 이어갈 대화를
// 가리키는 플래그와, 켜져 있다면 권한 확인 생략 플래그.
//
// 메인 세션은 뒤에 --add-dir 을 잇고 레포 세션은 여기서 끝난다. 공통부를 한 곳에 두는 이유는
// 두 세션이 같은 옵션에 다르게 반응하면 사용자가 그 차이를 세션 안에서야 발견하기 때문이다.
func claudeCommand(bypassPermissions bool, conversationFlags []string) []string {
	command := make([]string, 0, 2+len(conversationFlags))
	command = append(command, sessionCommandName)
	command = append(command, conversationFlags...)
	if bypassPermissions {
		command = append(command, skipPermissionsFlagName)
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
	if request.loadRepoClaudeMd {
		return []sessionEnvironmentEntry{{name: repoClaudeMdEnvName, value: repoClaudeMdEnvValue}}
	}
	return nil
}
