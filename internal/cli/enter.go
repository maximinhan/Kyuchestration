package cli

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/maximinhan/Kyuchestration/internal/session"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 인자 없이 실행한 kyu 다 — 이 도구를 여는 문이다.
//
// 사용자의 머릿속에서 이것은 한 동작이다: "이 디렉토리에서 작업을 시작한다".
// 초기화·세션 생성·진입은 도구의 사정이지 사용자의 단계가 아니므로 여기서 한 번에 잇는다.
//
// 세 걸음을 직접 조립하지 않고 기존 명령을 순서대로 부른다. 진입 플로우가 자기만의 조립을
// 갖기 시작하면 kyu 와 kyu start 가 서로 다른 세션을 띄우게 되고, 그 어긋남은 사용자가
// 세션 안에서 --add-dir 이 빠진 것을 발견할 때에야 드러난다.

const enterUsageText = `사용법: kyu [--bypass-permissions]

  kyu                       이 디렉토리에서 작업 시작 — 초기화·메인 세션 생성·진입까지 한 번에
  kyu --bypass-permissions  메인 세션의 claude 를 권한 확인 없이 띄운다`

// entryGuidanceForExplicitInit 은 자동 초기화를 거절할 때 함께 내보내는 다음 걸음이다.
//
// 길을 막는 것이 아니라 한 번 더 말하게 하는 장치라, 그 방법을 같은 자리에서 알린다.
const entryGuidanceForExplicitInit = "cd 로 워크디렉토리에 들어간 뒤 실행하세요.\n" +
	"정말 여기를 워크디렉토리로 쓰려면 kyu init 을 직접 실행하세요."

// entryWarningForEmptyWorkDir 는 레포가 하나도 없는 워크디렉토리에서 세션을 만들 때의 경고다.
//
// 진입을 막지는 않는다 — 무엇을 클론할지 정하는 것부터가 메인 세션이 할 일이다(설계 문서 5.4).
// 다만 --add-dir 목록은 세션을 만드는 순간 굳으므로, 세션 안에서 클론한 레포는 다음 세션에서야 붙는다.
// 그 사실을 진입 전에 말하지 않으면 사용자는 세션 안에서 클론한 레포가 왜 안 보이는지 알 수 없다.
const entryWarningForEmptyWorkDir = "레포 없음 — 메인 세션에는 지금 이 디렉토리에 있는 레포만 붙습니다.\n" +
	"세션 안에서 클론한 레포까지 붙이려면 kyu kill main 뒤에 kyu 를 다시 실행하세요."

// EnterWorkDir 는 인자 없는 kyu 를 실행한다: 초기화 → 메인 세션 생성 → 진입.
//
// 몇 번을 실행해도 결과가 같다. 계획 파일이 이미 있으면 만들지 않고, 세션이 이미 떠 있으면
// 만들지 않고 붙기만 한다 — 사용자가 "지금 상태가 어떻더라" 를 먼저 확인하지 않아도 되게 하는 것이
// 이 명령의 값어치다.
//
// 경고는 errOut 으로 나간다. 진입 안내와 섞이면 세션에 들어가기 직전 화면에서 두 종류의 말이
// 구분 없이 흘러간다.
func EnterWorkDir(out, errOut io.Writer, args []string, backend session.SessionBackend) error {
	request, err := parseEnterArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	if err := initializeWorkDirForEntry(out, location.absolutePath); err != nil {
		return err
	}

	// 세션 생존을 먼저 묻는다. StartSession 도 이미 있는 세션을 실패로 다루지는 않지만,
	// 그 경로는 "이미 실행 중입니다 / 진입: kyu attach main" 을 찍는다 — 바로 그 진입을 지금
	// 대신 해주는 명령에서 그 안내는 사용자가 할 일이 남은 것처럼 읽힌다.
	mainSessionName := session.MainSessionName(location.name)
	isMainSessionAlive, err := backend.IsAlive(mainSessionName)
	if err != nil {
		return fmt.Errorf("메인 세션 생존 확인 실패 (%s): %w", mainSessionName, err)
	}

	if isMainSessionAlive {
		// 진입을 막지는 않는다. 사용자가 원한 것은 이 워크디렉토리에서 작업을 시작하는 것이고,
		// 떠 있는 세션이 그 요구를 이미 채운다 — 다만 이번에 켠 옵션이 그 세션에는 닿지 않는다.
		if request.bypassPermissions {
			if err := warnRunningSessionKeepsTheCommandItWasCreatedWith(errOut, mainRowLabel); err != nil {
				return err
			}
		}
	} else {
		if err := warnWhenWorkDirHasNoRepo(errOut, location.absolutePath); err != nil {
			return err
		}
		if err := StartSession(out, errOut, request.startArgs(), backend); err != nil {
			return err
		}
	}

	return AttachSession(out, []string{mainRowLabel}, backend)
}

// enterRequest 는 파싱이 끝난 kyu 요청이다.
type enterRequest struct {
	// bypassPermissions 는 메인 세션의 claude 가 권한 확인을 건너뛸지다.
	bypassPermissions bool
}

// parseEnterArgs 는 인자 없는 kyu 가 받는 옵션을 고른다.
//
// 레포 이름은 받지 않는다. 이 명령이 만드는 것은 메인 세션 하나이고, 레포 세션을 띄우는 길은
// kyu start <repo> 로 이미 있다.
func parseEnterArgs(args []string) (enterRequest, error) {
	var request enterRequest

	for _, arg := range args {
		if arg != bypassPermissionsOptionName {
			return enterRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, enterUsageText)
		}
		request.bypassPermissions = true
	}

	return request, nil
}

// startArgs 는 이 요청을 kyu start 의 인자로 옮긴다.
//
// 파싱한 구조체를 넘기지 않고 인자 문자열로 되돌린다. 진입 플로우가 start 의 내부 표현을
// 직접 건드리기 시작하면 같은 옵션을 두 곳에서 해석하게 되고, 그 어긋남은 사용자가 세션
// 안에서야 발견한다 — 옵션의 해석자는 start 의 파서 하나로 남긴다.
func (request enterRequest) startArgs() []string {
	if request.bypassPermissions {
		return []string{bypassPermissionsOptionName}
	}
	return nil
}

// initializeWorkDirForEntry 는 계획 파일이 아직 없으면 kyu init 과 같은 것을 만든다.
func initializeWorkDirForEntry(out io.Writer, absoluteWorkDirPath string) error {
	planFilePath := workdir.PlanFilePath(absoluteWorkDirPath)

	_, err := os.Stat(planFilePath)
	if err == nil {
		return nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		// 읽지 못하는 이유가 부재가 아니면(권한 등) 만들려 들지 않는다. 그 자리에 사람이 쓴 계획이
		// 있는데 못 읽는 것일 수도 있고, 그때 O_EXCL 이 막아주더라도 사용자는 원인을 듣지 못한다.
		return fmt.Errorf("계획 파일 확인 실패 (%s): %w", planFilePath, err)
	}

	if err := refuseAutomaticInitInHomeOrFilesystemRoot(absoluteWorkDirPath); err != nil {
		return err
	}

	createdPlanFilePath, err := createWorkDirPlanFile(absoluteWorkDirPath)
	if err != nil {
		return err
	}

	// 조용히 만들지 않는다. 사용자는 자기 디렉토리에 무엇이 생겼는지 모른 채 세션 안으로 들어가게 된다.
	fmt.Fprintf(out, "워크디렉토리를 초기화했습니다: %s\n", createdPlanFilePath)
	return nil
}

// refuseAutomaticInitInHomeOrFilesystemRoot 는 홈 디렉토리와 파일시스템 루트에서 자동 초기화를 거절한다.
//
// 인자 없는 kyu 는 "여기가 워크디렉토리다" 라고 선언하는 명령이 되었다. cd 를 한 번 빠뜨리고
// 홈에서 실행하면 홈 전체가 워크디렉토리가 되어, 홈 아래 모든 레포가 메인 세션에 --add-dir 로
// 붙고 .coord/plan.md 가 홈에 남는다. 실수의 대가가 큰 자리라 자동으로는 만들지 않는다.
//
// 이미 초기화된 자리라면 여기까지 오지 않는다 — 거절하는 것은 자동 초기화뿐이고, 사용자가
// kyu init 으로 직접 선언한 뜻은 되돌리지 않는다.
func refuseAutomaticInitInHomeOrFilesystemRoot(absoluteWorkDirPath string) error {
	// filepath.Dir 가 자기 자신을 돌려주는 자리가 루트다.
	if absoluteWorkDirPath == filepath.Dir(absoluteWorkDirPath) {
		return fmt.Errorf("자동 초기화를 하지 않습니다 — 여기는 파일시스템 루트입니다: %s\n%s",
			absoluteWorkDirPath, entryGuidanceForExplicitInit)
	}

	homeDirectoryPath, err := os.UserHomeDir()
	if err != nil {
		// 홈이 어디인지 모르면 이 안전장치는 판단할 근거가 없다. 그렇다고 모든 진입을 막으면
		// 홈과 상관없는 워크디렉토리까지 함께 막힌다 — 안전장치가 도구를 못 쓰게 만드는 쪽이 더 나쁘다.
		return nil
	}

	// 경로 문자열로 비교하지 않는다. 홈이 심볼릭 링크를 거쳐 있거나 $HOME 이 끝에 슬래시를 달고
	// 있으면 같은 자리인데도 다른 문자열이 되어, 정작 막아야 할 실행을 그대로 통과시킨다.
	homeDirectoryInfo, err := os.Stat(homeDirectoryPath)
	if err != nil {
		// $HOME 이 가리키는 자리가 실제로 없는 환경도 있다. 그러면 여기는 홈이 아니다.
		return nil
	}
	workDirInfo, err := os.Stat(absoluteWorkDirPath)
	if err != nil {
		return fmt.Errorf("워크디렉토리 확인 실패 (%s): %w", absoluteWorkDirPath, err)
	}

	if os.SameFile(homeDirectoryInfo, workDirInfo) {
		return fmt.Errorf("자동 초기화를 하지 않습니다 — 여기는 홈 디렉토리입니다: %s\n%s",
			absoluteWorkDirPath, entryGuidanceForExplicitInit)
	}
	return nil
}

// warnWhenWorkDirHasNoRepo 는 붙일 레포가 하나도 없는 채로 메인 세션을 만든다는 사실을 알린다.
//
// StartSession 이 이미 훑는 것을 여기서 한 번 더 훑는다. 스캔 결과를 넘겨받으려면 start 의
// 시그니처를 진입 플로우 사정에 맞게 열어야 하는데, 그러면 kyu start 를 읽는 사람이 자기와
// 상관없는 인자를 먼저 이해해야 한다. 디렉토리 한 번 읽는 값보다 그쪽 대가가 크다.
func warnWhenWorkDirHasNoRepo(errOut io.Writer, absoluteWorkDirPath string) error {
	repos, err := workdir.ScanRepos(absoluteWorkDirPath)
	if err != nil {
		return err
	}
	if len(repos) > 0 {
		return nil
	}

	if _, err := fmt.Fprintln(errOut, entryWarningForEmptyWorkDir); err != nil {
		return fmt.Errorf("레포 없음 경고 출력 실패: %w", err)
	}
	return nil
}
