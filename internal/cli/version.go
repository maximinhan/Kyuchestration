package cli

import (
	"fmt"
	"io"
	"runtime/debug"
	"strings"
)

const versionUsageText = `사용법: kyu version

  kyu version   이 바이너리의 버전과 빌드에 쓰인 커밋을 출력한다`

// injectedVersion 은 릴리스 빌드가 링커로 주입하는 버전 문자열이다.
//
// 릴리스 워크플로가 이렇게 넣는다:
//
//	go build -ldflags "-X github.com/maximinhan/Kyuchestration/internal/cli.injectedVersion=v1.0.0" ./cmd/kyu
//
// 상수가 아니라 var 인 이유: 링커의 -X 는 상수 문자열로 초기화된 var 만 바꿀 수 있다.
//
// 주의 — 링커는 없는 심볼을 조용히 무시한다. 이 변수를 다른 패키지로 옮기면 빌드도 CI 도
// 그대로 통과하고, 릴리스 바이너리가 dev 라고 답할 때에야 어긋남이 드러난다.
// 그래서 심볼 경로를 cmd/kyu/main_test.go 가 실제로 주입해 보는 것으로 못박아 둔다.
var injectedVersion = developmentVersionName

// developmentVersionName 은 버전도 커밋도 알 수 없는 빌드가 자기를 부르는 이름이다.
const developmentVersionName = "dev"

// dirtyTreeMarker 는 커밋되지 않은 변경이 섞인 빌드에 붙는 표시다.
const dirtyTreeMarker = "+dirty"

// abbreviatedRevisionLength 는 출력에 싣는 커밋 해시의 길이다.
//
// 12 인 것은 go 가 유사 버전(v0.0.0-20260827083917-769e99f4150a)에 쓰는 길이와 같기 때문이다.
// 이 길이가 어긋나면 versionDescription 의 "이미 들어 있는 커밋인가" 판정이 성립하지 않는다.
const abbreviatedRevisionLength = 12

// PrintVersion 은 kyu version 을 실행한다.
//
// 릴리스 바이너리는 소스와 떨어져 돌아다닌다 — 새 머신에 복사해 둔 kyu 앞에서 "이게 어느
// 버전이지" 를 물을 수단이 저장소 쪽에는 없다. 그 질문에 바이너리 자신이 답하게 하는 명령이다.
func PrintVersion(out io.Writer, args []string) error {
	// version 에는 인자가 없다. 조용히 무시하면 오타로 넘긴 것이 통과해, 사용자는 자기가 무엇을
	// 물었는지 확인할 기회를 잃는다.
	if len(args) != 0 {
		return fmt.Errorf("version 은 인자를 받지 않습니다 (인자 %d 개를 받음)\n\n%s", len(args), versionUsageText)
	}

	// 두 번째 반환값을 버린다. 빌드 정보가 없는 것은 실패가 아니라 정보가 덜 붙은 빌드일 뿐이고,
	// versionDescription 이 nil 을 그 경우로 다룬다.
	buildInfo, _ := debug.ReadBuildInfo()
	fmt.Fprintln(out, versionDescription(buildInfo))
	return nil
}

// versionDescription 은 출력할 한 줄을 만든다.
//
// 빌드 정보를 안에서 읽지 않고 인자로 받는다. debug.ReadBuildInfo() 를 직접 부르면 테스트가
// 자기 자신을 빌드한 정보밖에 관찰하지 못해, go install 산출물이나 릴리스 바이너리의 경우를
// 재현할 방법이 없다.
func versionDescription(buildInfo *debug.BuildInfo) string {
	name := versionName(buildInfo)
	revision, wasBuiltFromDirtyTree := buildRevision(buildInfo)

	// go 1.24 부터 저장소에서 그냥 빌드한 바이너리의 모듈 버전은 유사 버전
	// (v0.0.0-20260827083917-769e99f4150a+dirty) 이라 커밋도 더러움 표시도 이미 들어 있다.
	// 그대로 덧붙이면 같은 해시가 한 줄에 두 번 찍힌다.
	if revision == "" || strings.Contains(name, revision) {
		return "kyu " + name
	}

	// 커밋 해시만 보여주면 "그 커밋을 빌드한 것" 으로 읽힌다. 워킹 트리가 더러웠다면 그 커밋에
	// 없는 변경이 섞여 있으므로, 표시하지 않으면 버그를 있지도 않은 자리에서 찾게 만든다.
	if wasBuiltFromDirtyTree {
		revision += dirtyTreeMarker
	}
	return "kyu " + name + " (" + revision + ")"
}

// versionName 은 이 바이너리가 자기를 부를 이름을 고른다. 주입된 버전 → 모듈 버전 → dev 순이다.
//
// 모듈 버전을 두 번째로 두는 것이 클론 없는 설치의 다른 한 축이다. go install <module>@latest 는
// 모듈 프록시가 준 zip 을 빌드하므로 .git 이 없고, 따라서 커밋(vcs.revision)도 링커 주입도 없다.
// 대신 go 가 Main.Version 에 태그(v1.0.0)나 커밋이 박힌 유사 버전을 적어 준다 — 그 경로로
// 설치한 바이너리의 출처는 여기서만 나온다.
func versionName(buildInfo *debug.BuildInfo) string {
	if injectedVersion != developmentVersionName {
		return injectedVersion
	}
	if buildInfo == nil {
		return developmentVersionName
	}

	// go 가 버전을 정하지 못한 빌드(.git 없이 받은 소스, -buildvcs=false)의 Main.Version 은
	// "(devel)" 이다. 버전이라기보다 "버전 없음" 의 다른 표현이라, 이 도구가 쓰는 이름으로 통일한다.
	if buildInfo.Main.Version == "" || buildInfo.Main.Version == "(devel)" {
		return developmentVersionName
	}
	return buildInfo.Main.Version
}

// buildRevision 은 빌드에 쓰인 커밋과 그때 워킹 트리가 더러웠는지를 돌려준다.
// 커밋이 박혀 있지 않으면 빈 문자열이다.
//
// go 는 .git 이 있는 자리에서 빌드할 때만 이 값을 넣는다. 즉 저장소에서 직접 빌드한 바이너리와
// 릴리스 워크플로의 산출물에는 붙고, 모듈 프록시를 거친 go install 산출물에는 없다.
func buildRevision(buildInfo *debug.BuildInfo) (revision string, wasBuiltFromDirtyTree bool) {
	if buildInfo == nil {
		return "", false
	}

	for _, setting := range buildInfo.Settings {
		switch setting.Key {
		case "vcs.revision":
			revision = setting.Value
		case "vcs.modified":
			wasBuiltFromDirtyTree = setting.Value == "true"
		}
	}

	if len(revision) > abbreviatedRevisionLength {
		revision = revision[:abbreviatedRevisionLength]
	}
	return revision, wasBuiltFromDirtyTree
}
