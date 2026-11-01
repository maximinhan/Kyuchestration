// Package installscript 는 저장소 루트의 install.sh 를 실제로 돌려 보는 시험만 담는다.
//
// 셸 스크립트의 시험 도구(bats)를 들이지 않고 Go 시험으로 돌린다 — CI 가 이미 go test ./... 를 돌리고, 시험이 필요한 것은
// "스크립트를 띄우고 파일 시스템을 본다" 뿐이다. 네트워크는 타지 않는다: GitHub API 를 흉내 내는 가짜 curl 을 PATH 앞에 둔다.
package installscript

import (
	"archive/tar"
	"compress/gzip"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// fakeCurl 은 install.sh 가 부르는 두 가지만 흉내 낸다 — 최신 릴리스 정보와 kyu 자산. --output 자리에 쓰고 --write-out 으로
// 상태 코드 200 을 낸다. 받는 kyu 는 version 에 답하는 셸 스크립트다.
//
// 불릴 때마다 자기 옆의 curl-calls.log 에 주소를 적는다. 로컬 파일만으로 설치할 때 네트워크에 한 번도 닿지 않았는지를 그 기록이
// 없다는 것으로 본다.
const fakeCurl = `#!/bin/sh
output=""
url=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="$2"; shift ;;
        --header | --write-out) shift ;;
        http*) url="$1" ;;
    esac
    shift
done
printf '%s\n' "$url" >> "$(dirname "$0")/curl-calls.log"
case "$url" in
    */releases/latest)
        printf '%s' '{"tag_name":"v9.9.9","assets":[{"url":"https://api.github.com/repos/maximinhan/Kyuchestration/releases/assets/1","name":"kyu_linux_amd64"}]}' > "$output" ;;
    */releases/assets/1)
        printf '#!/bin/sh\necho "kyu v9.9.9"\n' > "$output" ;;
    *) printf '404'; exit 0 ;;
esac
printf '200'
`

type installEnvironment struct {
	home    string
	fakeBin string
}

func newInstallEnvironment(t *testing.T) installEnvironment {
	t.Helper()
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		t.Skip("코어는 리눅스 x86-64 용만 낸다 — install.sh --with-core 가 이 플랫폼을 거절한다")
	}
	root := t.TempDir()
	environment := installEnvironment{home: filepath.Join(root, "home"), fakeBin: filepath.Join(root, "fake-bin")}
	for _, directory := range []string{environment.home, environment.fakeBin} {
		if err := os.MkdirAll(directory, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(environment.fakeBin, "curl"), []byte(fakeCurl), 0o755); err != nil {
		t.Fatal(err)
	}
	return environment
}

// coreArchive 는 코어 묶음 하나를 만든다. 실행 파일은 serveExitCode 로 끝나고, 이미지에 marker 파일을 둔다 — 설치 뒤 어느 묶음의
// 코어가 자리에 있는지를 그것으로 가린다.
func coreArchive(t *testing.T, marker string, serveExitCode int) string {
	t.Helper()
	archivePath := filepath.Join(t.TempDir(), "core.tar.gz")
	file, err := os.Create(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	compressed := gzip.NewWriter(file)
	defer compressed.Close()
	archive := tar.NewWriter(compressed)
	defer archive.Close()

	entries := []struct {
		name string
		mode int64
		body string
	}{
		{"kyuchestration-core/bin/kyuchestration-core", 0o755, "#!/bin/sh\nexit " + string(rune('0'+serveExitCode)) + "\n"},
		{"kyuchestration-core/marker", 0o644, marker},
		{"kyuchestration-core/VERSION", 0o644, "v9.9.9\n"},
	}
	for _, entry := range entries {
		header := &tar.Header{Name: entry.name, Mode: entry.mode, Size: int64(len(entry.body)), Typeflag: tar.TypeReg}
		if err := archive.WriteHeader(header); err != nil {
			t.Fatal(err)
		}
		if _, err := archive.Write([]byte(entry.body)); err != nil {
			t.Fatal(err)
		}
	}
	return archivePath
}

func (environment installEnvironment) runInstall(t *testing.T, archivePath string, extraEnvironment ...string) (string, error) {
	t.Helper()
	command := exec.Command("bash", "../../install.sh", "--with-core")
	command.Env = append([]string{
		"HOME=" + environment.home,
		"PATH=" + environment.fakeBin + ":/usr/bin:/bin",
		"KYU_CORE_ARCHIVE=" + archivePath,
	}, extraEnvironment...)
	output, err := command.CombinedOutput()
	return string(output), err
}

func (environment installEnvironment) installedMarker(t *testing.T) string {
	t.Helper()
	marker, err := os.ReadFile(filepath.Join(environment.home, ".local/share/kyuchestration/core/marker"))
	if err != nil {
		t.Fatalf("설치된 코어가 없다: %v", err)
	}
	return string(marker)
}

// 윈도우 화면은 wsl.exe --cd ~ -e .local/bin/kyuchestration-core 로 코어를 부른다. 그 자리는 화면과의 약속이라 kyu 를 다른 자리에
// 놓아도 움직이지 않는다 — 움직이면 화면은 코어를 못 찾고 같은 설치 명령을 되풀이해 안내한다.
func TestCoreLinkStaysAtTheScreensPlaceEvenWhenKyuGoesElsewhere(t *testing.T) {
	environment := newInstallEnvironment(t)
	otherBin := filepath.Join(environment.home, "tools/bin")

	output, err := environment.runInstall(t, coreArchive(t, "v1", 0), "KYU_INSTALL_DIR="+otherBin)
	if err != nil {
		t.Fatalf("설치가 실패했다: %v\n%s", err, output)
	}

	link := filepath.Join(environment.home, ".local/bin/kyuchestration-core")
	target, err := os.Readlink(link)
	if err != nil {
		t.Fatalf("~/.local/bin/kyuchestration-core 링크가 없다: %v\n%s", err, output)
	}
	if want := filepath.Join(environment.home, ".local/share/kyuchestration/core/bin/kyuchestration-core"); target != want {
		t.Fatalf("링크가 %s 를 가리킨다 — 기대 %s", target, want)
	}
	if _, err := os.Stat(filepath.Join(otherBin, "kyu")); err != nil {
		t.Fatalf("kyu 는 KYU_INSTALL_DIR 에 놓여야 한다: %v", err)
	}
}

// 새 코어가 뜨지 않으면 쓰던 코어가 그대로 남아야 한다 — 받다가 · 풀다가 · 띄워 보다가 어디서 끊겨도 같다.
func TestCoreThatDoesNotStartLeavesThePreviousCoreInPlace(t *testing.T) {
	environment := newInstallEnvironment(t)
	if output, err := environment.runInstall(t, coreArchive(t, "v1", 0)); err != nil {
		t.Fatalf("첫 설치가 실패했다: %v\n%s", err, output)
	}

	output, err := environment.runInstall(t, coreArchive(t, "v2-broken", 1))

	if err == nil {
		t.Fatalf("뜨지 않는 코어를 받아들였다\n%s", output)
	}
	if marker := environment.installedMarker(t); marker != "v1" {
		t.Fatalf("쓰던 코어(v1) 대신 %q 가 자리에 있다\n%s", marker, output)
	}
	link := filepath.Join(environment.home, ".local/bin/kyuchestration-core")
	if err := exec.Command(link, "serve", "--stdio").Run(); err != nil {
		t.Fatalf("남은 코어가 링크로 뜨지 않는다: %v", err)
	}
	leftovers, _ := filepath.Glob(filepath.Join(environment.home, ".local/share/kyuchestration/.core.staging.*"))
	if len(leftovers) != 0 {
		t.Fatalf("임시 자리가 남았다: %s", strings.Join(leftovers, ", "))
	}
}

// localKyuBinary 는 version 에 answer 로 답하는 kyu 하나를 둔다. 실행 비트를 일부러 뺀다 — CI 의 아티팩트처럼 권한을 잃고 건너온
// 파일도 설치가 실행 파일로 놓아야 한다.
func localKyuBinary(t *testing.T, answer string) string {
	t.Helper()
	binaryPath := filepath.Join(t.TempDir(), "kyu_linux_amd64")
	if err := os.WriteFile(binaryPath, []byte("#!/bin/sh\necho \""+answer+"\"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return binaryPath
}

func (environment installEnvironment) curlCalls() []string {
	calls, err := os.ReadFile(filepath.Join(environment.fakeBin, "curl-calls.log"))
	if err != nil {
		return nil
	}
	return strings.Fields(string(calls))
}

// 윈도우 화면은 msi 에 실린 묶음(kyu · 코어 이미지 · 이 스크립트)을 WSL 에 풀어 이 스크립트를 돌린다(core-ui-split-design.md 6.17
// 의 4a 정정). 두 파일이 다 손에 있으면 릴리스 정보도 물을 일이 없다 — 오프라인 첫 실행이 되고, 받은 판이 화면과 갈리지 않는다.
func TestInstallWithBothLocalFilesNeverReachesTheNetwork(t *testing.T) {
	environment := newInstallEnvironment(t)

	output, err := environment.runInstall(t, coreArchive(t, "local", 0),
		"KYU_BINARY_FILE="+localKyuBinary(t, "kyu v0.16.0"))

	if err != nil {
		t.Fatalf("설치가 실패했다: %v\n%s", err, output)
	}
	if calls := environment.curlCalls(); len(calls) != 0 {
		t.Fatalf("로컬 파일만으로 설치하는데 curl 을 불렀다: %s\n%s", strings.Join(calls, ", "), output)
	}
	if marker := environment.installedMarker(t); marker != "local" {
		t.Fatalf("KYU_CORE_ARCHIVE 의 코어 대신 %q 가 자리에 있다", marker)
	}
}

// 놓인 kyu 가 준 파일 그대로이고, 실행 파일이며, 설치가 그것을 version 으로 불러 본다. 준 파일은 그대로 남는다 — 화면이 묶음을
// 푼 자리를 스스로 치운다.
func TestKyuBinaryFileIsPlacedAsTheEngineAndAnswersVersion(t *testing.T) {
	environment := newInstallEnvironment(t)
	suppliedBinary := localKyuBinary(t, "kyu from-local-file")

	output, err := environment.runInstall(t, coreArchive(t, "local", 0), "KYU_BINARY_FILE="+suppliedBinary)

	if err != nil {
		t.Fatalf("설치가 실패했다: %v\n%s", err, output)
	}
	if !strings.Contains(output, "kyu from-local-file") {
		t.Fatalf("놓인 kyu 를 version 으로 불러 보지 않았다\n%s", output)
	}
	installedEngine := filepath.Join(environment.home, ".local/bin/kyu")
	installed, err := os.ReadFile(installedEngine)
	if err != nil {
		t.Fatalf("~/.local/bin/kyu 가 없다: %v\n%s", err, output)
	}
	supplied, err := os.ReadFile(suppliedBinary)
	if err != nil {
		t.Fatalf("준 kyu 파일이 사라졌다: %v", err)
	}
	if string(installed) != string(supplied) {
		t.Fatalf("놓인 kyu 가 준 파일과 다르다: %q", installed)
	}
	if info, err := os.Stat(installedEngine); err != nil || info.Mode().Perm()&0o111 == 0 {
		t.Fatalf("놓인 kyu 가 실행 파일이 아니다: %v %v", info.Mode(), err)
	}
	leftovers, _ := filepath.Glob(filepath.Join(environment.home, ".local/bin/.kyu.download.*"))
	if len(leftovers) != 0 {
		t.Fatalf("받는 자리가 남았다: %s", strings.Join(leftovers, ", "))
	}
}

// 화면은 깔린 코어의 판을 core/VERSION 한 자리에서 읽는다(4b). 손으로 깔든 화면이 깔든 그 파일이 코어 디렉토리에 남아야 한다.
func TestInstalledCoreKeepsItsVersionFile(t *testing.T) {
	environment := newInstallEnvironment(t)

	output, err := environment.runInstall(t, coreArchive(t, "v1", 0), "KYU_BINARY_FILE="+localKyuBinary(t, "kyu v9.9.9"))
	if err != nil {
		t.Fatalf("설치가 실패했다: %v\n%s", err, output)
	}

	version, err := os.ReadFile(filepath.Join(environment.home, ".local/share/kyuchestration/core/VERSION"))
	if err != nil {
		t.Fatalf("코어 디렉토리에 VERSION 이 없다: %v\n%s", err, output)
	}
	if string(version) != "v9.9.9\n" {
		t.Fatalf("VERSION 이 %q 다", version)
	}
}

// 준 파일이 없으면 릴리스로 떨어지지 않고 멈춘다 — 조용히 다른 판을 받아 오면 화면과 엔진의 판이 갈린다.
func TestMissingKyuBinaryFileStopsWithoutFallingBackToTheRelease(t *testing.T) {
	environment := newInstallEnvironment(t)

	output, err := environment.runInstall(t, coreArchive(t, "v1", 0),
		"KYU_BINARY_FILE="+filepath.Join(t.TempDir(), "no-such-kyu"))

	if err == nil {
		t.Fatalf("없는 KYU_BINARY_FILE 을 받아들였다\n%s", output)
	}
	if !strings.Contains(output, "KYU_BINARY_FILE") {
		t.Fatalf("무엇이 없는지 말하지 않았다\n%s", output)
	}
	if calls := environment.curlCalls(); len(calls) != 0 {
		t.Fatalf("릴리스로 떨어졌다: %s", strings.Join(calls, ", "))
	}
}
