package cli

import (
	"errors"
	"fmt"
	"io"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// 이 파일은 kyu auth add 다 — 물음 없이 토큰 하나를 프로필로 만드는 길.
//
// 대화형 등록(auth.go 의 registerTokenProfile)이 이미 있는데 이것이 따로 필요한 이유는 GUI 다.
// 앱은 "프로필 이름을 적으세요" 라는 물음에 답할 수 없다 — 자기 화면에서 이름과 토큰을 이미
// 받아둔 채로, 그것을 엔진에 건네고 성공·실패만 돌려받아야 한다.
//
// 두 경로가 나눠 갖는 것은 대화뿐이다. 토큰을 확인하고 저장하는 순서(verifyAndStoreToken),
// 평문 저장 경고, 저장했다는 안내 문구는 모두 공용이다. 그것들이 갈라지면 같은 머신에서
// 같은 일을 하는데 어느 문으로 들어왔는지에 따라 다른 일이 벌어진다.

// tokenMustComeFromStdinGuidance 는 토큰을 어디로 넘기는지 알린다.
//
// 인자로 받지 않는 이유를 함께 적는다. 그 사실을 모르면 사용자는 kyu auth add 개인 <토큰> 을
// 먼저 시도하게 되고, 그 한 번으로 토큰이 셸 히스토리와 ps 에 남는다.
const tokenMustComeFromStdinGuidance = "토큰을 받지 못했습니다 — 토큰은 stdin 으로 넘깁니다.\n" +
	"  예: printf %s \"$TOKEN\" | kyu auth add 개인\n" +
	"인자로는 받지 않습니다. 인자는 같은 머신의 다른 사용자가 ps 로 읽습니다."

// addTokenProfileRequest 는 파싱이 끝난 kyu auth add 요청이다.
type addTokenProfileRequest struct {
	// profileName 은 사용자가 이 토큰에 붙일 이름이다.
	profileName string

	// asJSON 은 사람용 안내 대신 기계용 문서를 낼지다.
	asJSON bool
}

// addTokenProfile 은 stdin 으로 받은 토큰을 확인하고 이름 붙여 저장한다.
//
// 물음과 경고는 errOut 으로 보낸다. stdout 은 --json 문서가 통째로 쓰는 자리라 한 줄이라도
// 섞이면 읽는 쪽의 파싱이 깨지고, 사람용 모드에서도 "무엇을 물었는가" 는 명령의 결과가 아니다.
func addTokenProfile(in io.Reader, out, errOut io.Writer, args []string, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore) error {
	request, err := parseAddTokenProfileArgs(args)
	if err != nil {
		return err
	}

	prompt := newInteractivePrompt(in, errOut)

	// 발급 안내는 실제 터미널에서만 낸다. 그 문단은 "지금 토큰을 발급받으러 갔다 오는 사람" 을
	// 위한 것이고, 파이프 너머에서 토큰을 이미 들고 있는 상대에게는 읽을 일 없는 소음이다.
	if prompt.inputFile != nil {
		fmt.Fprintf(errOut, "%s\n", tokenRegistrationGuidance)
	}

	if err := warnWhenTheTokenWouldBeStoredInPlaintext(errOut, tokenStore); err != nil {
		return err
	}

	token, err := prompt.askHidden(tokenQuestion)
	// 입력이 끝난 것은 이 명령에서는 취소가 아니다. 대화형 등록이라면 사용자가 그만둔 것이지만,
	// 여기서는 건네겠다고 부른 토큰이 오지 않은 것이라 성공으로 끝나서는 안 된다.
	if err != nil && !errors.Is(err, errInputClosed) {
		return err
	}
	if token == "" {
		return errors.New(tokenMustComeFromStdinGuidance)
	}

	_, owner, err := verifyAndStoreToken(request.profileName, token, tokenStore, newAccess)
	// 거절당했다는 사실만으로는 부르는 쪽이 "그래서 저장은 됐나" 를 알 수 없다. 대화형 등록은
	// 그 자리에서 "저장하지 않았습니다" 라고 말하는데, 그 보장이 여기서만 사라져서는 안 된다.
	if errors.Is(err, github.ErrInvalidToken) {
		return fmt.Errorf("%s 프로필에 아무것도 저장하지 않았습니다: %w", request.profileName, err)
	}
	if err != nil {
		return err
	}

	if request.asJSON {
		return writeAddedTokenProfileAsJSON(out, request.profileName, owner)
	}

	writeTokenStoredNotice(out, request.profileName, owner, tokenStore)
	return nil
}

// parseAddTokenProfileArgs 는 인자를 등록 요청으로 옮긴다.
//
// 이름 뒤의 인자를 전부 거절한다. 조용히 버리면 kyu auth add 개인 <토큰> 이 성공으로 끝나,
// 사용자는 자기가 인자로 넘긴 토큰이 등록됐다고 믿는다 — 실제로 등록된 것은 stdin 의 무언가다.
func parseAddTokenProfileArgs(args []string) (addTokenProfileRequest, error) {
	var request addTokenProfileRequest
	nameGiven := false

	for _, arg := range args {
		switch {
		case arg == machineJSONOptionName:
			request.asJSON = true

		case len(arg) > 0 && arg[0] == '-':
			return addTokenProfileRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, authUsageText)

		case nameGiven:
			return addTokenProfileRequest{}, fmt.Errorf("auth add 는 프로필 이름 하나만 받습니다 — 토큰은 stdin 으로 넘깁니다\n\n%s", authUsageText)

		default:
			request.profileName, nameGiven = arg, true
		}
	}

	if !nameGiven {
		return addTokenProfileRequest{}, fmt.Errorf("auth add 는 이 토큰에 붙일 프로필 이름이 필요합니다\n\n%s", authUsageText)
	}
	return request, nil
}
