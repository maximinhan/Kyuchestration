package github

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// cloneTokenEnvName 은 클론하는 git 자식 프로세스에 토큰을 건네는 환경변수 이름이다.
//
// 인자로 넘기지 않는 이유: 같은 머신의 다른 사용자가 ps 로 인자를 읽을 수 있다. 환경변수도
// /proc/<pid>/environ 으로 읽히지만 그것은 같은 사용자에게만 열려 있다.
const cloneTokenEnvName = "KYU_CLONE_TOKEN"

// tokenCredentialHelperScript 는 이번 클론에서만 자격증명을 답하는 credential helper 다.
//
// 이 한 줄이 "토큰을 디스크에 남기지 않는다" 의 실현이다. 흔한 다른 두 방법은 모두 흔적을 남긴다.
//
//   - 주소에 끼워 넣기(https://x-access-token:<토큰>@github.com/...): 그 주소가 그대로
//     .git/config 의 remote.origin.url 에 적힌다. 이후 fetch·push 마다 사용자에게 보인다.
//   - git config credential.helper store: 토큰이 홈 디렉토리의 파일에 평문으로 적힌다.
//
// helper 는 git 이 자격증명을 물어볼 때마다 실행되는 셸 조각이라, 토큰을 환경변수에서 그 순간에만
// 읽어 표준 출력으로 답하고 끝난다. -c 로 준 설정은 이번 실행에만 적용되고 어느 파일에도 적히지 않는다.
//
// 사용자 이름이 x-access-token 인 것은 GitHub 의 규약이다 — 토큰으로 인증할 때 사용자 이름 자리는
// 이 고정 문자열이고 비밀번호 자리에 토큰이 온다.
const tokenCredentialHelperScript = `!f() { echo username=x-access-token; echo "password=$` + cloneTokenEnvName + `"; }; f`

// CloneRepository 는 레포를 destinationPath 에 클론한다.
//
// git 을 직접 부른다. GitHub 이 주는 것은 결국 git 주소이고, 클론은 git 이 하는 일이다.
func (access *TokenAccess) CloneRepository(repository Repository, destinationPath string) error {
	if _, err := exec.LookPath("git"); err != nil {
		return fmt.Errorf("git 을 찾을 수 없습니다 — 설치 후 다시 실행하세요: %w", err)
	}

	command := exec.Command("git", cloneCommandArguments(repository.CloneURL, destinationPath)...)

	// 토큰은 여기서만 프로세스 경계를 넘는다. os.Environ() 을 그대로 이어받는 이유는 git 이
	// HOME·PATH·프록시 설정을 필요로 해서다 — 환경을 비우면 사용자의 프록시 뒤에서 클론이 끊긴다.
	command.Env = append(os.Environ(), cloneTokenEnvName+"="+access.token)

	// stdout 은 버린다. git clone 의 진행 표시는 stderr 로 나가고, stdout 에 오는 것은 없다.
	var stderr bytes.Buffer
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		// git 이 남긴 말을 함께 싣는다. "종료 코드 128" 만으로는 없는 레포인지 권한이 없는지 알 수 없다.
		// 토큰은 인자에도 주소에도 없으므로 이 메시지에 섞이지 않는다.
		return fmt.Errorf("git clone 실패 (%s): %w (%s)", repository.Name, err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

// cloneCommandArguments 는 git 에게 넘길 인자를 조립한다.
//
// 순수 함수로 떼어 둔 이유는 이 순서가 보안 결정 그 자체이기 때문이다. 실제 클론으로는
// "빈 helper 가 먼저 왔는가" 를 확인할 수 없다 — 그것이 빠져 있어도 클론은 성공하고,
// 대신 사용자의 전역 helper 가 토큰을 파일에 적어둔다.
func cloneCommandArguments(cloneURL, destinationPath string) []string {
	return []string{
		// 첫 -c 는 값이 빈 helper 다. git 에서 credential.helper 는 목록이고, 빈 값은 그 목록을
		// 비우라는 뜻이다 — 사용자의 전역·시스템 설정에 켜져 있는 helper 를 이번 실행에서만 끈다.
		"-c", "credential.helper=",
		"-c", "credential.helper=" + tokenCredentialHelperScript,
		"clone", cloneURL, destinationPath,
	}
}
