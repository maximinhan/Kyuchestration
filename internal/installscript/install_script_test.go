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
