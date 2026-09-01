#!/usr/bin/env bash
#
# 뀨케스트레이션 데스크톱 앱 설치 스크립트 — 최신 릴리스에서 이 머신에 맞는 설치 패키지를 받는다.
#
#   curl -fsSL https://raw.githubusercontent.com/maximinhan/Kyuchestration/main/install-app.sh | bash
#
# 이미 받아 둔 dmg · deb 가 있으면 그 경로를 넘긴다.
#
#   curl -fsSL .../install-app.sh | bash -s -- ~/Downloads/kyuchestration-desktop_0.9.0_arm64.dmg
#
# 맥의 설치 자리는 /Applications 이고 KYU_APP_INSTALL_DIR 로 바꾼다.
#
# ── 이 스크립트가 있는 이유: 맥에서 앱이 열리게 하려고 ─────────────────────────────────
#
# 이 앱에는 애플 서명과 공증(notarization)이 없다 — 유료 개발자 계정이 필요해서다. 그런 앱을
# **브라우저로** 받으면 macOS 가 받은 파일에 com.apple.quarantine 확장 속성을 붙이고,
# Gatekeeper 는 그 속성이 붙은 것만 검사한다. macOS 15 Sequoia 부터는 그 검사에서 막힌 앱에
# "그래도 열기" 버튼조차 주지 않는다 — 예전의 우클릭 → 열기 우회가 사라졌다.
#
# 격리 속성을 붙이는 주체는 **받는 프로그램**이지 파일 형식이 아니다. 브라우저는 붙이고 curl 은
# 붙이지 않는다. 그래서 같은 dmg 라도 이 스크립트로 받으면 Gatekeeper 가 아예 관여하지 않고
# 그대로 열린다. 서명 · 공증을 붙이기 전까지 맥에서 앱을 여는 가장 짧은 길이다.
#
# 리눅스에는 이 문제가 없다. 그래도 같은 스크립트가 deb 를 받아 주는 이유는 "어느 파일이 내
# 것인가" 를 사람이 고르지 않아도 되게 하려는 것뿐이다 — 설치 자체는 관리자 권한이 필요해
# 이 스크립트가 대신하지 않는다(explain_deb_install 참고).
#
# ── 겹치는 코드를 공용 파일로 빼지 않은 이유 ────────────────────────────────────────
#
# install.sh 와 릴리스 조회 · 자산 내려받기가 겹친다. 공용 파일로 빼려면 실행 도중에 그것을 한 번
# 더 받아 와야 한다 — 두 스크립트 모두 "curl 한 줄" 로 실행되는 것이 존재 이유이기 때문이다.
# 받는 자리가 둘이 되면 실패할 수 있는 자리도 둘이 되고, 파이프로 실행되는 스크립트가 인터넷에서
# 코드를 더 받아 실행하는 자리가 생긴다. 그 값이 겹치는 서른 줄보다 비싸다.
#
# sh 가 아니라 bash 를 쓰되 macOS 에 기본으로 깔린 bash 3.2 에서 도는 범위로만 썼다 —
# install.sh 와 같은 이유로 배열을 쓰지 않는다(3.2 는 set -u 아래에서 빈 배열 전개를 오류로 본다).

set -euo pipefail

readonly REPOSITORY="maximinhan/Kyuchestration"
readonly GITHUB_API_BASE_URL="https://api.github.com"
readonly GITHUB_API_VERSION="2022-11-28"

# cleanup 이 EXIT 에서 치우는 것들.
TEMP_DIR=""
MOUNT_POINT=""
STAGING_PATH=""
DOWNLOAD_PATH=""

# 아래 둘은 부르는 쪽이 결과를 받는 자리다. 명령 치환은 서브셸이라 그 안에서 전역에 넣은 값이
# 사라지므로, 전역을 건드리는 함수는 stdout 으로 값을 돌려주지 않는다.
RELEASE_JSON_PATH=""
PACKAGE_PATH=""

# 무인증으로 통과했으면 빈 문자열로 남는다. resolve_release_access 가 정한다.
RESOLVED_GITHUB_TOKEN=""

cleanup() {
    # 붙인 dmg 를 먼저 뗀다. 붙어 있는 채로 아래에서 그 파일을 지우면 볼륨만 남는다.
    [ -n "$MOUNT_POINT" ] && hdiutil detach "$MOUNT_POINT" > /dev/null 2>&1
    [ -n "$STAGING_PATH" ] && rm -rf "$STAGING_PATH"
    [ -n "$DOWNLOAD_PATH" ] && rm -f "$DOWNLOAD_PATH"
    [ -n "$TEMP_DIR" ] && rm -rf "$TEMP_DIR"
    # 마지막 [ ] 가 거짓이면 트랩 전체가 실패로 끝나므로 성공을 명시한다.
    return 0
}
trap cleanup EXIT

# fail 은 원인을 stderr 로 알리고 종료 코드 1 로 끝낸다. 인자 하나가 한 줄이다.
fail() {
    printf '%s\n' "$@" >&2
    exit 1
}

# report 는 진행 상황과 안내를 stderr 로 낸다.
# 몇몇 함수가 stdout 으로 값을 돌려주므로, 그 자리에 사람이 읽는 줄을 섞지 않는다.
report() {
    printf '%s\n' "$*" >&2
}

# detect_target_platform 은 이 머신에 맞는 <os>_<arch> 를 돌려준다.
#
# 릴리스가 실제로 내는 조합만 인정한다(.github/workflows/release.yml 의 desktop-deb · desktop-dmg).
# 없는 조합을 그냥 흘려보내면 "받는 중" 을 찍고 404 로 끝나는데, 그러면 사용자는 자기 플랫폼이
# 대상이 아니라는 것을 모른 채 네트워크부터 의심하게 된다.
detect_target_platform() {
    local kernel_name machine_name target_os target_arch
    kernel_name=$(uname -s)
    machine_name=$(uname -m)

    case "$kernel_name" in
        Linux) target_os="linux" ;;
        Darwin) target_os="darwin" ;;
        *)
            fail "지원하지 않는 운영체제입니다: $kernel_name" \
                 "데스크톱 앱은 맥과 리눅스(WSL 포함) 설치 패키지만 냅니다." \
                 "윈도우 네이티브 패키지는 아직 내지 않습니다. WSL 안에서 리눅스 패키지를 쓰세요."
            ;;
    esac

    case "$machine_name" in
        x86_64 | amd64) target_arch="amd64" ;;
        arm64 | aarch64) target_arch="arm64" ;;
        *) fail "지원하지 않는 아키텍처입니다: $machine_name" ;;
    esac

    case "${target_os}_${target_arch}" in
        darwin_arm64 | darwin_amd64 | linux_amd64) ;;
        *)
            fail "이 플랫폼용 설치 패키지는 아직 내지 않습니다: ${target_os}/${target_arch}" \
                 "소스에서 띄우세요: git clone https://github.com/${REPOSITORY}.git && cd Kyuchestration/desktop && ./gradlew run"
            ;;
    esac

    printf '%s_%s\n' "$target_os" "$target_arch"
}

# github_api_get 은 URL 을 받아 본문을 파일에 쓰고 HTTP 상태 코드를 stdout 으로 돌려준다.
# 네 번째 인자가 비어 있으면 무인증으로 부른다.
#
# curl 에 -f 를 주지 않는다. -f 는 4xx 를 그냥 실패로 만들어 상태 코드를 삼키는데, 이 스크립트는
# 403(레이트리밋)인지 404(릴리스 없음)인지를 보고 무엇을 알릴지 가른다.
#
# 두 curl 호출이 헤더 하나만 빼고 같다. 배열로 헤더를 모으면 한 번에 쓸 수 있지만, bash 3.2 는
# set -u 아래에서 빈 배열 전개를 오류로 보므로 macOS 기본 셸에서 그대로 죽는다.
github_api_get() {
    local url="$1"
    local accept_header="$2"
    local output_path="$3"
    local token="${4:-}"

    if [ -n "$token" ]; then
        curl --silent --show-error --location \
            --header "Accept: ${accept_header}" \
            --header "Authorization: Bearer ${token}" \
            --header "X-GitHub-Api-Version: ${GITHUB_API_VERSION}" \
            --output "$output_path" \
            --write-out '%{http_code}' \
            "$url"
    else
        curl --silent --show-error --location \
            --header "Accept: ${accept_header}" \
            --header "X-GitHub-Api-Version: ${GITHUB_API_VERSION}" \
            --output "$output_path" \
            --write-out '%{http_code}' \
            "$url"
    fi
}

# resolve_release_access 는 최신 릴리스 정보를 RELEASE_JSON_PATH 에 담고,
# 그때 통한 토큰을 RESOLVED_GITHUB_TOKEN 에 남긴다(무인증으로 통했으면 빈 문자열).
#
# 인증은 "없으면 없는 대로 먼저 해 본다" 순서다. 이 저장소는 공개라 평소에는 무인증으로 끝나고,
# 토큰은 레이트리밋에 걸린 자리(CI 등)에서만 쓰인다. 순서를 뒤집으면 필요 없는 토큰을 계속
# 요구하는 스크립트가 되고, 그것이 필요 없어졌다는 사실을 아무도 알아채지 못한다.
resolve_release_access() {
    local latest_release_url="${GITHUB_API_BASE_URL}/repos/${REPOSITORY}/releases/latest"
    local http_status

    http_status=$(github_api_get "$latest_release_url" "application/vnd.github+json" "$RELEASE_JSON_PATH") \
        || fail "GitHub API 에 연결하지 못했습니다: ${latest_release_url}" \
                "네트워크와 프록시 설정을 확인하세요."

    if [ "$http_status" = "200" ]; then
        RESOLVED_GITHUB_TOKEN=""
        return 0
    fi

    if [ -z "${GITHUB_TOKEN:-}" ]; then
        case "$http_status" in
            404)
                fail "${REPOSITORY} 에 아직 릴리스가 없습니다." \
                     "v 로 시작하는 태그를 밀면 릴리스가 만들어집니다 (.github/workflows/release.yml)."
                ;;
            403 | 429)
                fail "GitHub API 레이트리밋에 걸렸습니다 (${http_status})." \
                     "잠시 뒤 다시 실행하거나, 토큰을 내보내고 다시 실행하세요:" \
                     "  export GITHUB_TOKEN=<개인 액세스 토큰>"
                ;;
            *)
                fail "GitHub API 가 예상하지 못한 응답을 돌려줬습니다: ${http_status}"
                ;;
        esac
    fi

    report "무인증 요청이 거절돼(${http_status}) GITHUB_TOKEN 으로 다시 시도합니다."
    http_status=$(github_api_get "$latest_release_url" "application/vnd.github+json" \
        "$RELEASE_JSON_PATH" "$GITHUB_TOKEN") \
        || fail "GitHub API 에 연결하지 못했습니다: ${latest_release_url}"

    if [ "$http_status" != "200" ]; then
        fail "토큰을 붙여도 최신 릴리스를 읽지 못했습니다 (${http_status})." \
             "토큰이 만료됐거나 이 저장소를 보지 못하는지 확인하세요."
    fi

    RESOLVED_GITHUB_TOKEN="$GITHUB_TOKEN"
}

# release_tag_name 은 릴리스 정보에서 태그 이름(v0.9.0)을 뽑는다.
#
# jq 를 쓰지 않는다 — 설치 스크립트가 다른 도구부터 깔라고 하면 "한 줄로 끝" 이라는 이 경로의
# 이유가 사라진다. 자산 이름에 버전이 들어 있어서(kyuchestration-desktop_0.9.0_arm64.dmg) 받을
# 파일 이름을 알려면 이 값이 먼저 필요하다.
release_tag_name() {
    tr -d ' \t' < "$1" \
        | tr ',' '\n' \
        | awk '
            match($0, /"tag_name":"[^"]*"/) {
                value = substr($0, RSTART, RLENGTH)
                sub(/^"tag_name":"/, "", value)
                sub(/"$/, "", value)
                print value
                exit
            }
        '
}

# asset_api_url_for 는 릴리스 정보에서 자산 하나의 API 주소를 뽑는다. 없으면 아무것도 내지 않는다.
#
# GitHub 응답에서 자산 객체는 url 이 name 보다 먼저 온다. 그래서 자산 주소를 볼 때마다 기억해
# 두었다가, 찾는 이름을 만나면 직전에 본 것을 답한다.
asset_api_url_for() {
    local release_json_path="$1"
    local asset_name="$2"

    tr -d ' \t' < "$release_json_path" \
        | tr ',' '\n' \
        | awk -v wanted="\"name\":\"${asset_name}\"" '
            match($0, /"url":"[^"]*\/releases\/assets\/[0-9]+"/) {
                candidate = substr($0, RSTART, RLENGTH)
                sub(/^"url":"/, "", candidate)
                sub(/"$/, "", candidate)
            }
            index($0, wanted) > 0 && candidate != "" {
                print candidate
                exit
            }
        '
}

# download_latest_package 는 최신 릴리스에서 이 머신용 설치 패키지를 받아 PACKAGE_PATH 에 담는다.
#
# browser_download_url 이 아니라 API 자산 주소를 쓴다. browser_download_url 은 웹 세션 인증을
# 전제하므로 토큰을 붙인 curl 과 맞지 않는다. API 주소에 Accept: application/octet-stream 을
# 주면 실제 바이트가 있는 자리로 리다이렉트된다.
download_latest_package() {
    local package_format="$1"
    local asset_arch="$2"
    local download_dir="$3"
    local release_tag desktop_version asset_name asset_url destination_path http_status

    RELEASE_JSON_PATH="${TEMP_DIR}/release.json"
    resolve_release_access

    release_tag=$(release_tag_name "$RELEASE_JSON_PATH")
    [ -n "$release_tag" ] || fail "최신 릴리스에서 태그 이름을 읽지 못했습니다."

    desktop_version="${release_tag#v}"
    asset_name="kyuchestration-desktop_${desktop_version}_${asset_arch}.${package_format}"

    report "최신 릴리스: ${release_tag}"

    asset_url=$(asset_api_url_for "$RELEASE_JSON_PATH" "$asset_name")
    if [ -z "$asset_url" ]; then
        fail "최신 릴리스에 ${asset_name} 자산이 없습니다." \
             "릴리스가 이 플랫폼 설치 패키지를 함께 올렸는지 확인하세요:" \
             "  https://github.com/${REPOSITORY}/releases/latest"
    fi

    destination_path="${download_dir}/${asset_name}"

    # 받는 도중에 죽으면 반쯤 받은 파일이 남는다. cleanup 이 지울 수 있게 먼저 적어 두고,
    # 다 받은 뒤에 지운다 — 리눅스에서는 이 파일이 스크립트보다 오래 살아야 하기 때문이다.
    DOWNLOAD_PATH="$destination_path"

    report "받는 중: ${asset_name}"
    http_status=$(github_api_get "$asset_url" "application/octet-stream" \
        "$destination_path" "$RESOLVED_GITHUB_TOKEN") \
        || fail "자산을 내려받지 못했습니다: ${asset_url}"

    if [ "$http_status" != "200" ]; then
        fail "자산을 내려받지 못했습니다 (${http_status}): ${asset_url}"
    fi

    DOWNLOAD_PATH=""
    PACKAGE_PATH="$destination_path"
}

# fail_if_app_is_running 은 바꿔 끼울 앱이 지금 돌고 있으면 멈춘다.
#
# 맥은 돌고 있는 .app 을 지우고 새로 놓는 것을 막지 않는다. 돌던 프로세스는 이미 연 파일을 계속
# 쥐고 있어서 그 순간에는 멀쩡해 보이지만, 그 뒤로 앱이 자기 자원을 읽으러 갈 때마다 없어진 것을
# 찾게 된다. 원인이 설치였다는 것을 그때는 알 수 없으므로 여기서 끊는다.
fail_if_app_is_running() {
    local destination_path="$1"
    local running_commands

    # 명령줄 목록을 먼저 변수에 담고 나서 찾는다. ps 의 출력을 grep 에 곧바로 이어 붙이면
    # ps 가 찍는 그 순간에 grep 도 이미 떠 있어서, grep 이 자기 명령줄에 있는 찾는 문자열을
    # 보고 "돌고 있다" 고 답한다. 담아 두면 ps 가 도는 시점에 grep 이 아직 없다.
    # (맥 러너 실측: 앱이 설치도 되지 않은 자리에서 이 검사가 걸렸다.)
    #
    # pgrep 을 쓰지 않는 이유는 따로 있다 — pgrep -f 는 패턴을 정규식으로 읽어서 경로 안의
    # 문자가 뜻을 갖는다. 설치 자리는 KYU_APP_INSTALL_DIR 로 바뀔 수 있으므로 글자 그대로 찾는다.
    # shellcheck disable=SC2009
    running_commands=$(ps -A -o command=) \
        || fail "실행 중인 프로세스 목록을 읽지 못했습니다 (ps)."

    if printf '%s\n' "$running_commands" | grep -qF -- "${destination_path}/Contents/MacOS/"; then
        fail "앱이 실행 중입니다: ${destination_path}" \
             "끝낸 뒤 다시 실행하세요 (⌘Q 또는 Dock 에서 종료)."
    fi
}

# install_app_from_dmg 는 dmg 를 붙여 그 안의 .app 을 설치 자리에 놓는다.
install_app_from_dmg() {
    local dmg_path="$1"
    local install_dir="$2"
    local source_app app_name destination_path replaced_existing

    MOUNT_POINT="${TEMP_DIR}/mount"
    mkdir -p "$MOUNT_POINT"

    hdiutil attach -nobrowse -readonly "$dmg_path" -mountpoint "$MOUNT_POINT" > /dev/null \
        || fail "dmg 를 붙이지 못했습니다: ${dmg_path}"

    # 앱 이름을 적어 두지 않고 찾는다 — 이름은 desktop/build.gradle.kts 의 packageName 에서
    # 오므로, 그것이 바뀌면 여기도 함께 바뀌어야 하는 자리를 만들지 않는다.
    source_app=$(find "$MOUNT_POINT" -maxdepth 1 -type d -name '*.app')
    if [ "$(printf '%s\n' "$source_app" | grep -c .)" -ne 1 ]; then
        fail "dmg 안의 앱이 하나여야 하는데 이렇게 나왔습니다: ${source_app:-없음}"
    fi

    app_name=$(basename "$source_app")
    destination_path="${install_dir}/${app_name}"

    fail_if_app_is_running "$destination_path"

    replaced_existing="no"
    [ -d "$destination_path" ] && replaced_existing="yes"

    mkdir -p "$install_dir"

    # 옆에 놓고 마지막에 자리를 바꾼다. 기존 앱을 먼저 지우고 복사하면, 복사가 중간에 실패했을 때
    # 사용자에게는 앱이 아예 없는 상태가 남는다. 같은 파일 시스템 안의 mv 는 이름만 바꾸므로
    # 그 사이에 앱이 반쯤 있는 순간이 없다.
    STAGING_PATH="${install_dir}/.${app_name}.install.$$"
    rm -rf "$STAGING_PATH"

    # cp -R 이 아니라 ditto 를 쓴다. .app 안에는 심볼릭 링크와 확장 속성이 함께 있고, 번들을
    # 그대로 옮기는 것이 ditto 의 일이다. 맥에 기본으로 들어 있어 따로 깔 것도 없다.
    ditto "$source_app" "$STAGING_PATH" \
        || fail "앱을 복사하지 못했습니다: ${install_dir}" \
                "이 자리에 쓸 권한이 없다면 자기 홈에 넣으세요:" \
                "  export KYU_APP_INSTALL_DIR=\"\$HOME/Applications\""

    # 격리 속성을 뗀다. curl 로 받았다면 애초에 붙지 않아 아무 일도 하지 않는다. 브라우저로 받아 둔
    # 파일을 인자로 넘긴 경우에만 실제로 뗀다 — 자기가 방금 자기 손으로 받은 파일을 자기 머신에서
    # 여는 일이라 정당하다. 속성이 없을 때 xattr 은 오류로 끝나므로 그 자리를 삼킨다.
    xattr -dr com.apple.quarantine "$STAGING_PATH" 2> /dev/null || true

    rm -rf "$destination_path"
    mv "$STAGING_PATH" "$destination_path"
    STAGING_PATH=""

    # 복사가 끝났다는 것이 앱이 온전하다는 뜻은 아니다. 여기서 보지 않으면 껍데기만 옮겨진 것을
    # 사용자가 눌러 봐야 안다.
    [ -d "${destination_path}/Contents/MacOS" ] \
        || fail "옮긴 앱이 온전하지 않습니다: ${destination_path}"

    report ""
    if [ "$replaced_existing" = "yes" ]; then
        report "기존 설치를 교체했습니다: ${destination_path}"
    else
        report "설치 완료: ${destination_path}"
    fi
    report ""
    # 이름(open -a Kyuchestration)이 아니라 경로로 안내한다. 방금 복사해 놓은 앱은
    # LaunchServices 에 아직 등록되지 않아 이름으로는 찾지 못할 수 있다 — 맥 인텔 러너에서
    # 그 자리를 실측했다("Unable to find application named 'Kyuchestration'"). 경로는 등록과
    # 무관하게 언제나 통한다.
    report "여는 법 — Finder 의 응용 프로그램에서 두 번 누르거나:"
    report "  open \"${destination_path}\""
    report ""
    report "curl 로 받은 파일에는 격리 속성이 붙지 않아 경고 없이 열립니다."
    report "업데이트는 같은 명령을 다시 실행하면 됩니다 — 항상 최신 릴리스로 덮어씁니다."
}

# explain_deb_install 은 받아 둔 deb 를 어떻게 설치하는지 알리고 끝난다.
#
# 스크립트가 sudo apt install 까지 하지 않는다. 이 스크립트는 curl 로 받아 그 자리에서 실행되는
# 코드라, 그것이 스스로 관리자 권한을 쥐면 받은 사람은 무엇에 권한을 주는지 보지 못한 채 넘기게
# 된다. 관리자 권한이 필요한 한 줄은 사람이 직접 친다.
explain_deb_install() {
    local deb_path="$1"

    report ""
    report "설치 패키지: ${deb_path}"
    report ""
    report "설치는 관리자 권한이 필요해 이 스크립트가 대신하지 않습니다. 다음 한 줄을 실행하세요."
    report "  sudo apt install \"${deb_path}\""
    report ""
    report "apt 가 없으면 sudo dpkg -i 로 넣고, 빠진 의존 패키지는 sudo apt-get install -f 로 채웁니다."
}

main() {
    local local_package_path target_platform package_format asset_arch
    local install_dir required_command

    local_package_path="${1:-}"

    for required_command in curl uname awk tr mktemp find basename; do
        command -v "$required_command" > /dev/null 2>&1 \
            || fail "${required_command} 가 필요합니다."
    done

    target_platform=$(detect_target_platform)
    asset_arch="${target_platform#*_}"
    case "$target_platform" in
        darwin_*) package_format="dmg" ;;
        *) package_format="deb" ;;
    esac

    if [ "$package_format" = "dmg" ]; then
        # 맥에서만 쓰는 것들. 위의 공통 목록에 섞으면 리눅스에서 있지도 않은 도구를 요구한다.
        for required_command in hdiutil ditto xattr; do
            command -v "$required_command" > /dev/null 2>&1 \
                || fail "${required_command} 가 필요합니다 (맥 기본 도구입니다)."
        done
    fi

    TEMP_DIR=$(mktemp -d)

    report "대상: ${target_platform}"

    if [ -n "$local_package_path" ]; then
        [ -f "$local_package_path" ] \
            || fail "받아 둔 파일을 찾지 못했습니다: ${local_package_path}"
        case "$local_package_path" in
            *".${package_format}") ;;
            *) fail "이 머신에 맞는 것은 ${package_format} 입니다: ${local_package_path}" ;;
        esac
        PACKAGE_PATH="$local_package_path"
        report "받아 둔 파일을 씁니다: ${PACKAGE_PATH}"
    elif [ "$package_format" = "dmg" ]; then
        # dmg 는 앱을 꺼내고 나면 쓸 일이 없어 임시 자리에 받는다. cleanup 이 치운다.
        download_latest_package "$package_format" "$asset_arch" "$TEMP_DIR"
    else
        # deb 는 이어서 사람이 sudo apt install 로 써야 하므로 지금 있는 디렉토리에 남긴다.
        download_latest_package "$package_format" "$asset_arch" "$PWD"
    fi

    if [ "$package_format" = "dmg" ]; then
        install_dir="${KYU_APP_INSTALL_DIR:-/Applications}"
        install_app_from_dmg "$PACKAGE_PATH" "$install_dir"
    else
        explain_deb_install "$PACKAGE_PATH"
    fi
}

main "$@"
