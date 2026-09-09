package cli

import (
	"bytes"
	"errors"
	"fmt"
	"io"

	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// 이 파일은 kyu claude-auth 다 — 앱이 세션에 실어 보낼 claude 자격 증명을 맡아 두는 자리.
//
// **auth 아래가 아니라 따로 두는 이유.** `kyu auth` 는 GitHub 토큰을 이름 붙여 여러 개 두는 명령이고,
// 이쪽은 이름 없이 하나만 둔다. 같은 낱말 아래에 두면 `kyu auth list` 가 무엇을 나열하는지가
// 흐려지고, 저장 자리도 서로 덮지 않게 갈라져 있다(secretstore 의 이름 공간).
//
// **여기서 토큰을 확인하지 않는다.** `kyu auth add` 는 GitHub 에 먼저 물어보고 저장하는데,
// 이 명령은 받은 것을 그대로 저장한다. 이 엔진에는 claude 에게 물을 클라이언트가 없고, 물으려면
// claude 를 자식으로 띄워 한 턴을 써야 한다 — 그 턴은 실제로 청구된다. 확인은 그것을 화면에서
// 이끄는 쪽(앱)이 하고, 앱은 확인이 끝난 토큰만 이리로 보낸다(chat-ui-design.md 7.3 라).
// 그래서 status 가 답하는 것은 "저장돼 있다" 이지 "통한다" 가 아니다.

const claudeAuthUsageText = `사용법: kyu claude-auth <set|token|status|clear>

  kyu claude-auth set      토큰을 stdin 으로 받아 저장한다
  kyu claude-auth token    저장된 토큰을 stdout 으로 낸다 — 값이 그대로 찍힌다
  kyu claude-auth status   토큰이 저장돼 있는지, 어디에 저장되는지 (값은 찍지 않는다)
  kyu claude-auth clear    저장된 토큰을 지운다 (없어도 성공으로 끝난다)

옵션 (set, status):
  --json                   사람용 출력 대신 기계용 JSON 을 낸다 (GUI·스크립트 연동용)

토큰은 인자가 아니라 stdin 으로 받는다 — 인자는 같은 머신의 다른 사용자가 ps 로 읽는다.
저장된 토큰은 앱이 세션의 claude 에게 CLAUDE_CODE_OAUTH_TOKEN 으로 물려준다.

이 명령은 토큰이 유효한지 확인하지 않는다. 확인은 앱이 claude 를 한 턴 띄워서 한다.`

// claudeTokenMustComeFromStdinGuidance 는 토큰을 어디로 넘기는지 알린다.
const claudeTokenMustComeFromStdinGuidance = "토큰을 받지 못했습니다 — 토큰은 stdin 으로 넘깁니다.\n" +
	"  예: printf %s \"$TOKEN\" | kyu claude-auth set\n" +
	"인자로는 받지 않습니다. 인자는 같은 머신의 다른 사용자가 ps 로 읽습니다."

// claudeTokenRegistrationGuidance 는 토큰을 받기 전에 실제 터미널에서만 보여주는 안내다.
//
// 어디서 얻는지를 이 자리에 적는다. 이 토큰은 GitHub 토큰처럼 웹 화면에서 발급받는 것이 아니라
// **이미 로그인된 머신에서** claude 자신이 만들어 주는 것이라, 그 사실을 모르면 사용자는
// 발급 페이지를 찾아 헤맨다.
const claudeTokenRegistrationGuidance = `claude 자격 증명 토큰을 등록합니다.

  얻는 법: 이미 로그인된 머신의 터미널에서 claude setup-token
           (Claude 구독이 있어야 합니다)

브라우저로 로그인할 수 있는 머신이라면 이 명령이 필요 없습니다 — claude auth login 이
자격 증명을 스스로 챙깁니다. 이 자리는 그 길이 막힌 머신을 위한 폴백입니다.

이 토큰은 이 머신에만 저장되고 앤트로픽 말고 어디에도 보내지 않습니다.
`

// claudePlaintextStorageWarning 은 토큰이 평문 파일에 저장될 때의 경고다. 받기 전에 낸다.
const claudePlaintextStorageWarning = "경고: 이 머신에는 키체인도 secret-service 도 없어 토큰을 설정 파일에 평문으로 저장합니다 (권한 0600).\n" +
	"      토큰을 남기고 싶지 않다면 지금 중단하고(Ctrl-C), 브라우저 로그인이 되는 머신에서 쓰세요.\n"

// claudeTokenQuestion 은 토큰을 받는 자리의 물음이다.
const claudeTokenQuestion = "claude 자격 증명 토큰 (입력은 보이지 않습니다): "

// noClaudeTokenGuidance 는 저장된 것이 없을 때 보여주는 다음 걸음이다.
const noClaudeTokenGuidance = `저장된 claude 토큰이 없습니다.

  kyu claude-auth set   토큰을 stdin 으로 넘겨 저장한다
`

// ClaudeTokenStore 는 claude 자격 증명 토큰 하나를 맡아 두는 저장소다.
//
// 인터페이스로 받는 이유는 GitHub 쪽(secretstore.TokenStore)과 같다 — 이 명령의 흐름을
// 진짜 키체인 없이 검증할 수 있어야 하고, 어느 구현을 쓸지는 진입점의 결정이다.
type ClaudeTokenStore interface {
	// SaveToken 은 토큰을 저장한다. 이미 있으면 덮어쓴다.
	SaveToken(token string) error

	// LoadToken 은 저장된 토큰을 꺼낸다. 없으면 secretstore.ErrClaudeTokenNotStored 다.
	LoadToken() (string, error)

	// RemoveToken 은 저장된 토큰을 지운다. 없어도 성공으로 끝난다.
	RemoveToken() error

	// StoredStorageKind 는 저장된 토큰이 놓인 자리다. 저장된 것이 없으면 두 번째 값이 false 다.
	StoredStorageKind() (secretstore.StorageKind, bool, error)

	// StorageKindForNewToken 은 지금 저장하면 토큰이 놓일 자리다.
	StorageKindForNewToken() secretstore.StorageKind
}

// ManageClaudeCredentials 는 kyu claude-auth 를 실행한다.
func ManageClaudeCredentials(in io.Reader, out, errOut io.Writer, args []string, tokenStore ClaudeTokenStore) error {
	if len(args) == 0 {
		return fmt.Errorf("claude-auth 는 하위 명령이 필요합니다\n\n%s", claudeAuthUsageText)
	}

	switch args[0] {
	case "set":
		return setClaudeToken(in, out, errOut, args[1:], tokenStore)

	case "token":
		return writeStoredClaudeToken(out, args[1:], tokenStore)

	case "status":
		return reportClaudeTokenStatus(out, args[1:], tokenStore)

	case "clear":
		return clearClaudeToken(out, args[1:], tokenStore)

	default:
		return fmt.Errorf("알 수 없는 claude-auth 명령: %s\n\n%s", args[0], claudeAuthUsageText)
	}
}

// setClaudeToken 은 stdin 으로 받은 토큰을 저장한다.
//
// 물음과 경고는 errOut 으로 보낸다. stdout 은 --json 문서가 통째로 쓰는 자리라 한 줄이라도
// 섞이면 읽는 쪽의 파싱이 깨진다.
func setClaudeToken(in io.Reader, out, errOut io.Writer, args []string, tokenStore ClaudeTokenStore) error {
	asJSON, err := parseClaudeAuthOptions("set", args)
	if err != nil {
		return err
	}

	prompt := newInteractivePrompt(in, errOut)

	// 얻는 법 안내는 실제 터미널에서만 낸다. 파이프 너머에서 토큰을 이미 들고 있는 상대(앱)에게는
	// 읽을 일 없는 소음이고, 그 소음은 앱의 오류 화면에 그대로 올라간다.
	if prompt.readsFromATerminal() {
		fmt.Fprintf(errOut, "%s\n", claudeTokenRegistrationGuidance)
	}

	if tokenStore.StorageKindForNewToken() == secretstore.StorageConfigFile {
		if _, err := fmt.Fprint(errOut, claudePlaintextStorageWarning); err != nil {
			return fmt.Errorf("평문 저장 경고 출력 실패: %w", err)
		}
	}

	token, err := prompt.askHidden(claudeTokenQuestion)
	// 입력이 끝난 것은 취소가 아니다. 건네겠다고 부른 토큰이 오지 않은 것이라 성공으로 끝나서는 안 된다.
	if err != nil && !errors.Is(err, errInputClosed) {
		return err
	}
	if token == "" {
		return errors.New(claudeTokenMustComeFromStdinGuidance)
	}

	if err := tokenStore.SaveToken(token); err != nil {
		return fmt.Errorf("claude 토큰 저장 실패: %w", err)
	}

	storage := tokenStore.StorageKindForNewToken()
	if asJSON {
		return writeStoredClaudeTokenAsJSON(out, storage)
	}

	fmt.Fprintf(out, "claude 토큰을 저장했습니다 (%s).\n", storage.Description())
	return nil
}

// writeStoredClaudeToken 은 저장된 토큰을 stdout 에 그대로 낸다.
//
// **이 명령만 값을 찍는다.** 앱이 세션을 띄우기 직전에 부르는 자리이고, 앱이 그 값을 자식 환경에
// 실어야 하므로 어딘가에서는 값이 나와야 한다. 그 자리를 하나로 좁혀 두면 "토큰이 이 도구 밖으로
// 나가는 통로" 를 셀 수 있다 — 여기 하나다.
//
// 개행을 붙이지 않는다. 받는 쪽이 무엇을 걷어내야 하는지 판단할 필요 없이, 나온 바이트가 곧
// 저장된 값이다. stdin 으로 받을 때 한 글자도 덧붙이지 않는 것과 같은 규율이다.
//
// --json 을 받지 않는다. 문서에 토큰을 담으면 그 문서는 로그와 파일에 그대로 남는데, 이 값은
// 폐기 전까지 계속 유효하다(auth_json.go 가 토큰을 어느 문서에도 담지 않는 이유와 같다).
func writeStoredClaudeToken(out io.Writer, args []string, tokenStore ClaudeTokenStore) error {
	if len(args) != 0 {
		return fmt.Errorf("claude-auth token 은 인자를 받지 않습니다: %s\n\n%s", args[0], claudeAuthUsageText)
	}

	token, err := tokenStore.LoadToken()
	if errors.Is(err, secretstore.ErrClaudeTokenNotStored) {
		return errors.New(noClaudeTokenGuidance)
	}
	if err != nil {
		return fmt.Errorf("저장된 claude 토큰을 꺼내지 못했습니다: %w", err)
	}

	if _, err := io.WriteString(out, token); err != nil {
		return fmt.Errorf("claude 토큰 출력 실패: %w", err)
	}
	return nil
}

// reportClaudeTokenStatus 는 토큰이 저장돼 있는지와 새 토큰이 놓일 자리를 답한다.
//
// 값을 꺼내지 않는다 — 저장소에 값을 물어보지도 않는다. 상태를 확인하는 화면은 공유되거나
// 캡처되기 쉬운 자리이고, 한 번 새 나간 토큰은 폐기 전까지 계속 유효하다.
func reportClaudeTokenStatus(out io.Writer, args []string, tokenStore ClaudeTokenStore) error {
	asJSON, err := parseClaudeAuthOptions("status", args)
	if err != nil {
		return err
	}

	storedStorage, isStored, err := tokenStore.StoredStorageKind()
	if err != nil {
		return err
	}
	storageForNewToken := tokenStore.StorageKindForNewToken()

	if asJSON {
		return writeClaudeTokenStatusAsJSON(out, storedStorage, isStored, storageForNewToken)
	}

	if !isStored {
		var rendered bytes.Buffer
		fmt.Fprint(&rendered, noClaudeTokenGuidance)
		fmt.Fprintf(&rendered, "\n지금 저장하면 놓일 자리: %s\n", storageForNewToken.Description())
		_, err := out.Write(rendered.Bytes())
		return err
	}

	fmt.Fprintf(out, "claude 토큰이 저장돼 있습니다 (%s).\n", storedStorage.Description())
	fmt.Fprintf(out, "\n지우기: kyu claude-auth clear\n")
	return nil
}

func clearClaudeToken(out io.Writer, args []string, tokenStore ClaudeTokenStore) error {
	if len(args) != 0 {
		return fmt.Errorf("claude-auth clear 는 인자를 받지 않습니다: %s\n\n%s", args[0], claudeAuthUsageText)
	}

	if err := tokenStore.RemoveToken(); err != nil {
		return fmt.Errorf("claude 토큰 삭제 실패: %w", err)
	}

	// 지울 것이 없었는지 있었는지를 가려 말하지 않는다. 부르는 사람이 원한 것은 "이 머신에 내
	// 토큰이 남아 있지 않은 상태" 이고, 그 상태는 어느 쪽이든 같다.
	fmt.Fprint(out, "이 머신에 저장된 claude 토큰이 없습니다.\n")
	return nil
}

// parseClaudeAuthOptions 는 하위 명령에 붙은 인자를 읽는다. 받는 것은 --json 하나뿐이다.
//
// 모르는 인자를 조용히 버리지 않는다. 오타 난 옵션(--jsonn)을 흘려보내면 사람용 문구가 나오는데,
// 그것을 파싱하려던 쪽에는 "JSON 이 깨졌다" 로만 보인다.
func parseClaudeAuthOptions(subcommandName string, args []string) (bool, error) {
	asJSON := false
	for _, arg := range args {
		if arg != machineJSONOptionName {
			return false, fmt.Errorf("claude-auth %s 는 %s 말고 인자를 받지 않습니다: %s\n\n%s",
				subcommandName, machineJSONOptionName, arg, claudeAuthUsageText)
		}
		asJSON = true
	}
	return asJSON, nil
}
