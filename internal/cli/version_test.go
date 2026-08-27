package cli

import (
	"bytes"
	"runtime/debug"
	"strings"
	"testing"
)

// withInjectedVersion 은 -ldflags 주입을 흉내 낸다.
//
// injectedVersion 은 링커가 바꾸는 패키지 변수라 테스트가 건드릴 것이 이것 하나뿐이다.
// 되돌려 놓지 않으면 다음 테스트가 "주입된 빌드" 를 보게 되므로 Cleanup 으로 복원한다.
func withInjectedVersion(t *testing.T, version string) {
	t.Helper()

	original := injectedVersion
	injectedVersion = version
	t.Cleanup(func() { injectedVersion = original })
}

// buildInfoWith 는 관찰하고 싶은 빌드 정보를 만든다.
//
// 실제 debug.ReadBuildInfo() 는 이 테스트 바이너리 자신의 정보만 돌려주므로, go install 로 받은
// 바이너리나 릴리스 바이너리의 경우를 거기서는 재현할 수 없다.
func buildInfoWith(moduleVersion string, settings map[string]string) *debug.BuildInfo {
	buildInfo := &debug.BuildInfo{}
	buildInfo.Main.Version = moduleVersion
	for key, value := range settings {
		buildInfo.Settings = append(buildInfo.Settings, debug.BuildSetting{Key: key, Value: value})
	}
	return buildInfo
}

func TestVersionDescriptionPrefersTheVersionInjectedAtBuildTime(t *testing.T) {
	withInjectedVersion(t, "v1.2.3")

	got := versionDescription(buildInfoWith("(devel)", nil))

	if !strings.Contains(got, "v1.2.3") {
		t.Errorf("versionDescription() = %q, 주입된 v1.2.3 을 포함하기를 기대", got)
	}
}

func TestVersionDescriptionFallsBackToModuleVersionWhenNothingWasInjected(t *testing.T) {
	// go install <module>@latest 로 받은 바이너리의 자리다. 모듈 프록시가 준 zip 에는 .git 이
	// 없어 커밋이 박히지 않고, 대신 go 가 Main.Version 에 태그나 유사 버전을 적어 준다.
	// 클론 없이 설치한 바이너리의 출처는 이 값에서만 나온다.
	got := versionDescription(buildInfoWith("v0.0.0-20260827120000-abc123def456", nil))

	if !strings.Contains(got, "v0.0.0-20260827120000-abc123def456") {
		t.Errorf("versionDescription() = %q, 모듈 버전을 포함하기를 기대", got)
	}
}

func TestVersionDescriptionReportsDevWhenNeitherInjectedNorPublished(t *testing.T) {
	// 저장소에서 그냥 go build 한 바이너리다. go 는 이때 Main.Version 에 "(devel)" 을 적는데,
	// 버전이라기보다 "버전 없음" 의 다른 표현이라 그대로 내보내면 버전처럼 읽힌다.
	got := versionDescription(buildInfoWith("(devel)", nil))

	if !strings.Contains(got, developmentVersionName) {
		t.Errorf("versionDescription() = %q, %q 를 포함하기를 기대", got, developmentVersionName)
	}
	if strings.Contains(got, "(devel)") {
		t.Errorf("versionDescription() = %q, go 의 (devel) 을 그대로 내보내지 않기를 기대", got)
	}
}

func TestVersionDescriptionShowsTheCommitTheBinaryWasBuiltFrom(t *testing.T) {
	got := versionDescription(buildInfoWith("(devel)", map[string]string{
		"vcs.revision": "abc123def456789012345678901234567890abcd",
		"vcs.modified": "false",
	}))

	if !strings.Contains(got, "abc123def456") {
		t.Errorf("versionDescription() = %q, 빌드에 쓰인 커밋을 포함하기를 기대", got)
	}
}

func TestVersionDescriptionMarksBinariesBuiltFromADirtyWorkingTree(t *testing.T) {
	// 커밋 해시만 보여주면 "그 커밋을 빌드한 것" 으로 읽힌다. 실제로는 그 커밋에 없는 변경이
	// 섞여 있으므로, 표시하지 않으면 버그를 있지도 않은 자리에서 찾게 만든다.
	got := versionDescription(buildInfoWith("(devel)", map[string]string{
		"vcs.revision": "abc123def456789012345678901234567890abcd",
		"vcs.modified": "true",
	}))

	if !strings.Contains(got, "dirty") {
		t.Errorf("versionDescription() = %q, 더러운 워킹 트리 표시를 기대", got)
	}
}

func TestVersionDescriptionDoesNotRepeatACommitTheVersionAlreadyCarries(t *testing.T) {
	// go 1.24 부터 저장소에서 그냥 go build 한 바이너리의 모듈 버전은 유사 버전이고, 거기에
	// 커밋이 이미 들어 있다. 커밋을 무조건 덧붙이면 같은 해시가 한 줄에 두 번 찍힌다.
	got := versionDescription(buildInfoWith("v0.0.0-20260827083917-769e99f4150a", map[string]string{
		"vcs.revision": "769e99f4150a1234567890123456789012345678",
		"vcs.modified": "false",
	}))

	if strings.Count(got, "769e99f4150a") != 1 {
		t.Errorf("versionDescription() = %q, 커밋이 한 번만 나오기를 기대", got)
	}
}

func TestVersionDescriptionSurvivesBinariesWithoutBuildInfo(t *testing.T) {
	// debug.ReadBuildInfo() 는 실패할 수 있다(두 번째 반환값). 버전을 묻는 명령이 그때 죽으면
	// "무엇을 쓰고 있는지" 를 확인하려던 사람이 아무 답도 못 받는다.
	got := versionDescription(nil)

	if !strings.Contains(got, developmentVersionName) {
		t.Errorf("versionDescription(nil) = %q, %q 를 포함하기를 기대", got, developmentVersionName)
	}
}

func TestPrintVersionWritesOneLineToStdout(t *testing.T) {
	withInjectedVersion(t, "v1.2.3")

	var out bytes.Buffer
	if err := PrintVersion(&out, nil); err != nil {
		t.Fatalf("PrintVersion() 실패: %v", err)
	}

	printed := out.String()
	if !strings.HasPrefix(printed, "kyu v1.2.3") {
		t.Errorf("stdout = %q, \"kyu v1.2.3\" 로 시작하기를 기대", printed)
	}
	if strings.Count(printed, "\n") != 1 {
		t.Errorf("stdout = %q, 한 줄이기를 기대", printed)
	}
}

func TestPrintVersionRejectsArguments(t *testing.T) {
	// version 에는 인자가 없다. 조용히 무시하면 오타로 넘긴 것이 통과해 사용자가 자기가 무엇을
	// 물었는지 확인할 기회를 잃는다.
	var out bytes.Buffer
	err := PrintVersion(&out, []string{"--short"})

	if err == nil {
		t.Fatalf("PrintVersion(args) = nil, 거절하기를 기대")
	}
	if !strings.Contains(err.Error(), versionUsageText) {
		t.Errorf("에러 = %q, 사용법 안내를 포함하기를 기대", err)
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 거절한 명령이 stdout 을 오염시키지 않기를 기대", out.String())
	}
}
