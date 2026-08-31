package cli

import (
	"fmt"
	"io"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 kyu session-command 다 — 앱이 "무엇을 띄울까" 를 묻고 엔진이 답하는 자리.
//
// 앱은 claude 를 자기 PTY 에서 직접 보유한다(설계 문서 5.3). 그러면 앱도 메인 세션의 --add-dir
// 목록과 옵션이 claude 의 어느 플래그·환경변수로 옮겨지는지를 알아야 하는데, 그 지식이 두 곳에
// 있으면 갈라진다 — 갈라진 것을 발견하는 자리가 "메인 세션이 레포 지침을 안 읽는다" 이고,
// 그때 어느 쪽이 틀렸는지 알아내는 데 오래 걸린다(설계 문서 5.2.1 가).
//
// 그래서 조립은 kyu start 와 같은 함수(mainSessionCommand·claudeCommand)에서 가져온다.
// 이 파일이 따로 아는 것은 두 가지뿐이다 — 대화를 어느 플래그로 가리키는가, 그리고 그 답을
// 어떤 문서로 내보내는가.
//
// 세션 백엔드를 받지 않는다. 앱의 세션은 tmux 세션도 감독 세션도 아니라 엔진이 만들거나 죽이거나
// 셀 수단이 없고(설계 문서 5.1), 백엔드를 먼저 조립하면 tmux 없는 머신에서 앱이 세션을 열지
// 못하게 된다 — GUI 에서 tmux 의존이 빠지는 것이 이 전환의 보상 중 하나다(설계 문서 5.7).

// sessionIDFlagName 은 새 대화의 ID 를 부르는 쪽이 정해주는 플래그다.
//
// ID 를 알아내는 것이 아니라 정해주는 것이 이 설계의 핵심이다(설계 문서 5.5.1). 알아내는
// 길이었다면 claude 의 내부 저장 형식에 기대게 되고, 그것은 계약이 아니라 구현 세부라
// 판이 바뀔 때 조용히 어긋난다.
const sessionIDFlagName = "--session-id"

// resumeFlagName 은 이미 있는 대화를 이어가는 플래그다.
//
// 그 대화의 전사가 사라졌으면 claude 는 네트워크 왕복 없이 즉시 종료 코드 1 로 끝난다(실측).
// 앱 안에서는 PTY 가 곧바로 닫히는데, 그것을 사용자의 /exit 과 가르는 근거를 답 문서의
// resumedConversationId 가 준다(session_command_json.go · 설계 문서 5.5.4).
const resumeFlagName = "--resume"

// forgetConversationOptionName 은 적혀 있는 대화를 버리고 새 대화로 답하라는 뜻이다 —
// 화면의 "새 대화로 시작" 이 이 옵션으로 온다.
//
// 이름이 하는 일 쪽(기록을 잊는다)이지 바라는 것 쪽(새로 시작한다)이 아닌 이유는, 되돌릴 수
// 없는 쪽이 그것이어서다. 앱에서 이 옵션이 붙는 순간 앞 대화를 가리키던 기록은 사라진다.
//
// kyu start 에는 없다. CLI 세션은 대화 ID 를 갖지 않으므로(설계 문서 5.5.5 다) 버릴 기록도 없다.
const forgetConversationOptionName = "--forget-conversation"

const sessionCommandUsageText = `사용법: kyu session-command [repo] [옵션] --json

  kyu session-command --json            메인 세션이 실행할 것 (명령·cwd·더할 환경)
  kyu session-command <repo> --json     그 레포의 세션이 실행할 것

옵션:
  --bypass-permissions   claude 를 권한 확인 없이 띄운다 — 신뢰하는 워크디렉토리에서만
  --repo-claude-md       메인 세션이 각 레포의 CLAUDE.md 까지 읽게 한다 (메인 세션 전용)
  --forget-conversation  적혀 있는 대화를 버리고 새 대화로 답한다 — 앞 대화는 이어갈 수 없게 된다

이 명령은 --json 으로만 답한다 — 답이 사람이 읽고 무엇을 할 것이 아니라 앱이 실행할 것이다.
사람이 세션을 띄우는 자리는 kyu start 다.`

// AnswerSessionCommand 는 kyu session-command 를 실행한다. 세션을 띄우지 않고, 무엇을 띄울지 답한다.
//
// 대상 워크디렉토리는 명령을 실행한 디렉토리다 — kyu start 와 같다. 앱은 워크디렉토리 창 하나에
// 대해 이 명령을 부르므로 그 창의 디렉토리에서 실행하면 된다.
//
// errOut 은 대화 기록에 대해 할 말이 있을 때만 쓴다. stdout 은 문서 하나가 통째로 쓰는 자리라
// 사람에게 하는 말이 한 줄이라도 섞이면 앱의 파싱이 그대로 깨진다.
func AnswerSessionCommand(out, errOut io.Writer, args []string) error {
	request, err := parseSessionCommandArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	// 메인 세션이든 레포 세션이든 스캔이 먼저다. 메인은 붙일 레포 목록이 필요하고,
	// 레포 세션은 받은 이름이 실제 레포인지와 그 절대경로를 여기서만 알 수 있다.
	repos, err := workdir.ScanRepos(location.absolutePath)
	if err != nil {
		return err
	}

	if request.session.repoName == "" {
		return answerForMainSession(out, errOut, location, repos, request)
	}
	return answerForRepoSession(out, errOut, location, repos, request)
}

// sessionCommandRequest 는 파싱이 끝난 kyu session-command 요청이다.
//
// 공용 sessionRequest 를 그대로 쓰지 않고 감싼다. --forget-conversation 은 이 명령에만 있는
// 옵션이고, 공용 요청에 필드를 더하면 kyu start 의 파서가 영원히 채우지 않는 자리가 하나 생긴다 —
// 그 자리를 읽는 사람은 "start 도 이 옵션을 받는다" 로 읽는다.
type sessionCommandRequest struct {
	// session 은 두 명령이 함께 쓰는 부분이다. 조립을 같은 함수에서 가져오려면 이 모양이어야 한다.
	session sessionRequest

	// forgetRecordedConversation 은 적혀 있는 대화를 버리고 새 대화로 답할지다.
	forgetRecordedConversation bool
}

// parseSessionCommandArgs 는 인자를 세션 요청으로 옮긴다.
//
// kyu start 의 파서를 그대로 부르지 않는다. 받는 옵션은 같지만 거절할 때 실을 사용법이 다르고
// (여기에는 --json 이 필수다), 파싱은 갈라져도 어긋날 것이 없다 — 갈라지면 안 되는 것은
// 조립 지식이고 그쪽은 두 명령이 같은 함수를 부른다(설계 문서 5.5.5 다의 마지막 문단).
func parseSessionCommandArgs(args []string) (sessionCommandRequest, error) {
	var request sessionCommandRequest
	asJSON := false

	for _, arg := range args {
		switch {
		case arg == machineJSONOptionName:
			asJSON = true

		case arg == repoClaudeMdOptionName:
			request.session.loadRepoClaudeMd = true

		case arg == bypassPermissionsOptionName:
			request.session.bypassPermissions = true

		case arg == forgetConversationOptionName:
			request.forgetRecordedConversation = true

		// 모르는 옵션을 레포 이름으로 흘려보내면 "없는 레포입니다: --typo" 라는 엉뚱한 안내가 나온다.
		case strings.HasPrefix(arg, "-"):
			return sessionCommandRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, sessionCommandUsageText)

		case request.session.repoName != "":
			return sessionCommandRequest{}, fmt.Errorf("session-command 는 레포 이름 하나만 받습니다 (인자 %d 개를 받음)\n\n%s",
				len(args), sessionCommandUsageText)

		default:
			request.session.repoName = arg
		}
	}

	// 레포 세션은 자기 디렉토리에서 뜨므로 그 레포의 CLAUDE.md 를 이미 읽는다 — kyu start 와 같은 규칙이다.
	// 조용히 무시하면 앱은 무언가 켜졌다고 믿고, 그 믿음은 세션이 지침을 어길 때까지 드러나지 않는다.
	if request.session.loadRepoClaudeMd && request.session.repoName != "" {
		return sessionCommandRequest{}, fmt.Errorf("%s 는 메인 세션 전용입니다 — 레포 세션은 자기 디렉토리의 CLAUDE.md 를 이미 읽습니다",
			repoClaudeMdOptionName)
	}

	if !asJSON {
		return sessionCommandRequest{}, fmt.Errorf("session-command 는 기계용 문서로만 답합니다 (%s 옵션이 필요합니다) — 사람이 세션을 띄우는 자리는 kyu start 입니다\n\n%s",
			machineJSONOptionName, sessionCommandUsageText)
	}
	return request, nil
}

// answerForMainSession 은 워크디렉토리 최상위에서 열 조율용 세션을 답한다(설계 문서 5.4).
func answerForMainSession(out, errOut io.Writer, location workDirLocation, repos []workdir.Repo, request sessionCommandRequest) error {
	conversation, err := conversationForLabel(errOut, location.absolutePath, mainRowLabel, request)
	if err != nil {
		return err
	}

	return writeSessionCommandAsJSON(out,
		mainSessionCommand(repos, request.session, conversation.flags),
		location.absolutePath,
		sessionEnvironment(request.session),
		conversation)
}

// answerForRepoSession 은 레포 디렉토리에서 열 작업용 세션을 답한다.
//
// 없는 레포는 여기서 끝난다 — 대화를 배정하기 전이다. 답할 수 없는 물음에 대화를 적어두면
// 오타 하나가 다시는 쓰이지 않을 기록을 남기고, 사용자가 그것을 지울 이유를 알아낼 방법은 없다.
func answerForRepoSession(out, errOut io.Writer, location workDirLocation, repos []workdir.Repo, request sessionCommandRequest) error {
	repo, err := repoNamed(repos, request.session.repoName)
	if err != nil {
		return err
	}

	conversation, err := conversationForLabel(errOut, location.absolutePath, repo.Name, request)
	if err != nil {
		return err
	}

	return writeSessionCommandAsJSON(out,
		claudeCommand(request.session.bypassPermissions, conversation.flags),
		repo.AbsolutePath,
		sessionEnvironment(request.session),
		conversation)
}

// sessionConversation 은 이 세션이 쓸 대화 하나다 — claude 에게 그것을 가리키는 플래그와,
// 그것이 이어가기였는지.
//
// 플래그만 돌려주던 자리다. 답 문서가 "이어간 대화" 를 함께 실어야 하는데(설계 문서 5.5.4),
// 그 사실을 argv 에서 되읽게 하면 앱이 아니라 이 파일 안에서 조립 규칙을 두 번 알게 된다.
type sessionConversation struct {
	// flags 는 명령에 그대로 실릴 두 자리다 — --session-id <새 ID> 또는 --resume <적혀 있던 ID>.
	flags []string

	// resumedConversationID 는 이어가기로 연 대화의 ID 다. 새 대화면 비어 있다.
	resumedConversationID string
}

// conversationForLabel 은 이 라벨이 쓸 대화를 정해 claude 에게 가리키는 플래그로 옮긴다.
//
// 정하는 것이 곧 기록하는 것이다(설계 문서 5.5.3) — 조회처럼 보이는 이 걸음이
// .coord/conversations.json 을 쓴다. 만들어 놓고 적지 않으면 다음 물음이 다른 ID 를 만들고,
// 그러면 이어갈 대화가 매번 새로 생겨 이 표면의 목적이 성립하지 않는다.
//
// 대화 기록을 읽지 못한 것은 실패가 아니라 경고다. 앞선 대화를 잃을 뿐 새 대화는 열 수 있고,
// 여기서 실패로 끝내면 사용자는 자기가 쓴 적도 없는 파일을 손으로 지우기 전까지 앱에서
// 어떤 세션도 열지 못한다.
func conversationForLabel(
	errOut io.Writer,
	workDirPath, label string,
	request sessionCommandRequest,
) (sessionConversation, error) {
	assignment, err := assignConversation(workDirPath, label, request)
	if err != nil {
		return sessionConversation{}, err
	}

	for _, warning := range assignment.Warnings {
		if _, err := fmt.Fprintln(errOut, warning); err != nil {
			return sessionConversation{}, fmt.Errorf("대화 기록 경고 출력 실패: %w", err)
		}
	}

	if assignment.IsNewConversation {
		return sessionConversation{flags: []string{sessionIDFlagName, assignment.ConversationID}}, nil
	}
	return sessionConversation{
		flags:                 []string{resumeFlagName, assignment.ConversationID},
		resumedConversationID: assignment.ConversationID,
	}, nil
}

// assignConversation 은 요청이 고른 길로 대화를 정한다 — 이어가기가 기본이고, 새로 시작이 선택이다.
//
// 기본이 이어가기인 것이 이 표면의 자세다. 앱이 카드를 누를 때마다 무엇을 골라야 하는 것이 아니라,
// 고르지 않으면 대화가 이어진다(설계 문서 5.5.3).
func assignConversation(workDirPath, label string, request sessionCommandRequest) (workdir.ConversationAssignment, error) {
	if request.forgetRecordedConversation {
		return workdir.StartNewConversation(workDirPath, label)
	}
	return workdir.AssignConversation(workDirPath, label)
}
