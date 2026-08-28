#!/usr/bin/env bash
#
# kyu 설치 스크립트 — 최신 릴리스에서 이 머신에 맞는 바이너리를 받아 PATH 에 둔다.
#
#   curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github.raw" \
#     https://api.github.com/repos/maximinhan/Kyuchestration/contents/install.sh | bash
#
# 설치 위치는 ~/.local/bin 이고 KYU_INSTALL_DIR 로 바꾼다.
#
# 인증은 "없으면 없는 대로 먼저 해 본다" 순서다 — 무인증으로 먼저 부르고, 거절당했을 때에만
# GITHUB_TOKEN 을 쓴다. 이 저장소는 지금 프라이빗이지만 공개로 바꿀 예정이라, 그 순간
# 토큰 줄만 빼면 같은 명령이 그대로 동작해야 한다. 순서를 뒤집으면(토큰을 먼저 요구하면)
# 공개로 바꾼 뒤에도 토큰을 계속 달라고 하는 스크립트가 남고, 그것을 아무도 알아채지 못한다.
#
# sh 가 아니라 bash 를 쓴다. 다만 macOS 에 기본으로 깔린 bash 3.2 에서도 도는 범위로만 썼다 —
# 배열을 쓰지 않은 것이 그 때문이다(3.2 는 set -u 아래에서 빈 배열 전개를 오류로 본다).

set -euo pipefail

readonly REPOSITORY="maximinhan/Kyuchestration"
readonly GITHUB_API_BASE_URL="https://api.github.com"
readonly GITHUB_API_VERSION="2022-11-28"

# 받아 둔 임시 파일들. cleanup 이 EXIT 에서 지운다.
RELEASE_JSON_PATH=""
DOWNLOAD_PATH=""

# 무인증으로 통과했으면 빈 문자열로 남는다. resolve_release_access 가 정한다.
RESOLVED_GITHUB_TOKEN=""

cleanup() {
    [ -n "$RELEASE_JSON_PATH" ] && rm -f "$RELEASE_JSON_PATH"
    [ -n "$DOWNLOAD_PATH" ] && rm -f "$DOWNLOAD_PATH"
    # 마지막 [ ] 가 거짓이면 트랩 전체가 실패로 끝나므로 성공을 못박는다.
    return 0
}
trap cleanup EXIT

# fail 은 원인을 stderr 로 알리고 종료 코드 1 로 끝낸다. 인자 하나가 한 줄이다.
fail() {
    printf '%s\n' "$@" >&2
    exit 1
}

# report 는 진행 상황을 stderr 로 낸다.
# stdout 은 마지막의 kyu version 출력만 쓴다 — 설치 결과만 따로 받아 갈 수 있게 비워 둔다.
report() {
    printf '%s\n' "$*" >&2
}

# detect_target_platform 은 이 머신에 맞는 자산 이름의 꼬리(<os>_<arch>)를 돌려준다.
#
# 릴리스가 실제로 내는 조합만 인정한다. 없는 조합을 그냥 흘려보내면 "받는 중" 을 찍고 404 로
# 끝나는데, 그러면 사용자는 자기 플랫폼이 대상이 아니라는 것을 모른 채 토큰부터 의심하게 된다.
detect_target_platform() {
    local kernel_name machine_name target_os target_arch
    kernel_name=$(uname -s)
    machine_name=$(uname -m)

    case "$kernel_name" in
        Linux) target_os="linux" ;;
        Darwin) target_os="darwin" ;;
        *)
            fail "지원하지 않는 운영체제입니다: $kernel_name" \
                 "kyu 는 macOS 와 Linux(WSL 포함) 바이너리만 냅니다."
            ;;
    esac

    case "$machine_name" in
        x86_64 | amd64) target_arch="amd64" ;;
        arm64 | aarch64) target_arch="arm64" ;;
        *) fail "지원하지 않는 아키텍처입니다: $machine_name" ;;
    esac

    # 릴리스에 올라가는 조합은 셋뿐이다(.github/workflows/release.yml 의 크로스 컴파일 목록).
    case "${target_os}_${target_arch}" in
        linux_amd64 | darwin_amd64 | darwin_arm64) ;;
        *)
            fail "이 플랫폼용 바이너리는 아직 내지 않습니다: ${target_os}/${target_arch}" \
                 "소스에서 빌드하세요: go install github.com/${REPOSITORY}/cmd/kyu@latest"
            ;;
    esac

    printf '%s_%s\n' "$target_os" "$target_arch"
}

# github_api_get 은 URL 을 받아 본문을 파일에 쓰고 HTTP 상태 코드를 stdout 으로 돌려준다.
# 네 번째 인자가 비어 있으면 무인증으로 부른다.
#
# curl 에 -f 를 주지 않는다. -f 는 4xx 를 그냥 실패로 만들어 상태 코드를 삼키는데, 이 스크립트는
# 401 인지 404 인지를 보고 "토큰을 붙여 다시 해 볼 차례" 인지 "릴리스가 없는 것" 인지를 가른다.
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

# repository_is_visible 은 주어진 토큰(빈 문자열이면 무인증)으로 저장소 자체가 보이는지 답한다.
#
# releases/latest 의 404 는 두 가지를 같은 코드로 말한다 — GitHub 은 볼 수 없는 저장소의 존재
# 자체를 숨기기 때문이다. "릴리스가 아직 없다" 와 "이 저장소를 볼 수 없다" 를 가르려면
# 저장소를 한 번 더 물어보는 수밖에 없다.
repository_is_visible() {
    local token="${1:-}"
    local http_status

    http_status=$(github_api_get "${GITHUB_API_BASE_URL}/repos/${REPOSITORY}" \
        "application/vnd.github+json" /dev/null "$token") || return 1

    [ "$http_status" = "200" ]
}

fail_no_release_yet() {
    fail "${REPOSITORY} 에 아직 릴리스가 없습니다." \
         "v 로 시작하는 태그를 밀면 릴리스가 만들어집니다 (.github/workflows/release.yml)."
}

# explain_missing_token 은 무인증 접근이 거부됐고 토큰도 없을 때 무엇을 하면 되는지 알린다.
# 저장소가 public 이 된 뒤로 이 자리는 레이트리밋·네트워크 문제, 또는 저장소가
# 다시 프라이빗이 된 경우에만 닿는다 — 원인을 단정하지 않고 전부 알린다.
explain_missing_token() {
    fail "저장소 정보를 무인증으로 읽지 못했습니다 — 레이트리밋이거나, 네트워크 문제거나, 저장소가 프라이빗일 수 있습니다." \
         "" \
         "fine-grained personal access token 을 발급하세요:" \
         "  1. https://github.com/settings/personal-access-tokens/new" \
         "  2. Repository access → Only select repositories → ${REPOSITORY}" \
         "  3. Repository permissions → Contents: Read-only" \
         "  4. Expiration 을 짧게 잡으세요" \
         "" \
         "발급한 뒤 다시 실행하세요:" \
         "  export GITHUB_TOKEN=<발급한 토큰>"
}

# explain_token_rejection 은 토큰을 붙였는데도 거절당한 이유를 상태 코드별로 가려 알린다.
#
# 404 만 애매하다. GitHub 는 볼 수 없는 저장소의 존재 자체를 숨기므로, "릴리스가 아직 없다" 와
# "토큰이 이 저장소를 보지 못한다" 가 같은 코드로 온다. 저장소 자체를 한 번 더 물어 그 둘을 가른다.
explain_token_rejection() {
    local http_status="$1"
    local token="$2"

    case "$http_status" in
        401)
            fail "토큰이 유효하지 않습니다 (401)." \
                 "만료됐거나 잘못 붙여넣지 않았는지 확인하세요."
            ;;
        403)
            fail "토큰이 거절당했습니다 (403)." \
                 "Repository permissions 의 Contents 가 Read-only 이상인지 확인하세요."
            ;;
        404)
            if repository_is_visible "$token"; then
                fail_no_release_yet
            fi
            fail "토큰이 ${REPOSITORY} 를 보지 못합니다 (404)." \
                 "Only select repositories 목록에 이 저장소를 넣었는지 확인하세요."
            ;;
        *)
            fail "GitHub API 가 예상하지 못한 응답을 돌려줬습니다: ${http_status}"
            ;;
    esac
}

# resolve_release_access 는 최신 릴리스 정보를 RELEASE_JSON_PATH 에 담고,
# 그때 통한 토큰을 RESOLVED_GITHUB_TOKEN 에 남긴다(무인증으로 통했으면 빈 문자열).
#
# 토큰을 stdout 으로 돌려주지 않는 이유: 이 스크립트는 파이프로 실행되는 일이 많아,
# stdout 으로 흘린 값은 이어 붙인 자리의 로그에 그대로 남는다.
resolve_release_access() {
    local latest_release_url="${GITHUB_API_BASE_URL}/repos/${REPOSITORY}/releases/latest"
    local http_status

    http_status=$(github_api_get "$latest_release_url" "application/vnd.github+json" "$RELEASE_JSON_PATH") \
        || fail "GitHub API 에 연결하지 못했습니다: ${latest_release_url}" \
                "네트워크와 프록시 설정을 확인하세요."

    # 저장소가 공개로 바뀌면 여기서 끝난다 — 토큰을 넣어 둔 사람도, 넣지 않은 사람도 같은 길이다.
    if [ "$http_status" = "200" ]; then
        RESOLVED_GITHUB_TOKEN=""
        return 0
    fi

    case "$http_status" in
        401 | 403 | 404) ;;
        *) fail "GitHub API 가 예상하지 못한 응답을 돌려줬습니다: ${http_status}" ;;
    esac

    if [ -z "${GITHUB_TOKEN:-}" ]; then
        # 저장소가 공개로 바뀐 뒤에도 이 자리에 온다 — 그때의 404 는 "릴리스가 아직 없다" 다.
        # 가르지 않으면 토큰이 필요 없어진 뒤에도 토큰을 발급하라고 시키게 되고,
        # 그 안내가 틀렸다는 것을 아무도 알아채지 못한다.
        if repository_is_visible ""; then
            fail_no_release_yet
        fi
        explain_missing_token
    fi

    report "무인증 요청이 거절돼(${http_status}) GITHUB_TOKEN 으로 다시 시도합니다."
    http_status=$(github_api_get "$latest_release_url" "application/vnd.github+json" \
        "$RELEASE_JSON_PATH" "$GITHUB_TOKEN") \
        || fail "GitHub API 에 연결하지 못했습니다: ${latest_release_url}"

    if [ "$http_status" != "200" ]; then
        explain_token_rejection "$http_status" "$GITHUB_TOKEN"
    fi

    RESOLVED_GITHUB_TOKEN="$GITHUB_TOKEN"
}

# asset_api_url_for 는 릴리스 정보에서 자산 하나의 API 주소를 뽑는다. 없으면 아무것도 내지 않는다.
#
# jq 를 쓰지 않는다. 설치 스크립트가 다른 도구부터 깔라고 하면 "바이너리 하나 받아서 끝" 이라는
# 이 경로의 이유가 사라진다. 대신 우리가 정한 것만 본다 — 자산 이름은 릴리스 워크플로가
# kyu_<os>_<arch> 로 붙이고, 자산 주소는 .../releases/assets/<숫자> 한 형태뿐이다.
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

# download_asset 은 자산을 내려받아 실행 가능한 자리에 놓는다.
#
# browser_download_url 이 아니라 API 자산 주소를 쓴다. browser_download_url 은 웹 세션 인증을
# 전제하므로, 토큰을 붙인 curl 로는 프라이빗 저장소의 자산을 받지 못한다. API 주소에
# Accept: application/octet-stream 을 주면 실제 바이트가 있는 자리로 리다이렉트된다.
download_asset() {
    local asset_url="$1"
    local destination_path="$2"
    local http_status

    http_status=$(github_api_get "$asset_url" "application/octet-stream" \
        "$DOWNLOAD_PATH" "$RESOLVED_GITHUB_TOKEN") \
        || fail "자산을 내려받지 못했습니다: ${asset_url}"

    if [ "$http_status" != "200" ]; then
        fail "자산을 내려받지 못했습니다 (${http_status}): ${asset_url}"
    fi

    chmod +x "$DOWNLOAD_PATH"

    # 옆에 받아 두고 마지막에 자리를 바꾼다. 돌고 있는 바이너리를 직접 덮어쓰면 리눅스에서
    # Text file busy 로 실패하는데, 같은 파일 시스템 안의 mv 는 이름만 바꾸므로 그 일이 없다.
    mv "$DOWNLOAD_PATH" "$destination_path"
    DOWNLOAD_PATH=""
}

# warn_if_not_on_path 는 설치한 자리가 PATH 밖이면 알린다.
# 설치는 성공했는데 kyu 를 못 찾는 상태가 가장 헷갈리는 실패다.
warn_if_not_on_path() {
    local install_dir="$1"

    case ":${PATH}:" in
        *":${install_dir}:"*) return 0 ;;
    esac

    report ""
    report "주의: ${install_dir} 이 PATH 에 없습니다. 셸 설정에 다음을 넣으세요."
    report "  export PATH=\"${install_dir}:\$PATH\""
}

main() {
    local target_platform asset_name install_dir destination_path asset_url required_command

    for required_command in curl uname awk tr mktemp; do
        command -v "$required_command" > /dev/null 2>&1 \
            || fail "${required_command} 가 필요합니다."
    done

    target_platform=$(detect_target_platform)
    asset_name="kyu_${target_platform}"

    install_dir="${KYU_INSTALL_DIR:-${HOME}/.local/bin}"
    mkdir -p "$install_dir"
    destination_path="${install_dir}/kyu"

    RELEASE_JSON_PATH=$(mktemp)
    # 받는 자리를 설치할 디렉토리 안에 둔다. /tmp 에 받아 두면 파일 시스템이 달라 mv 가
    # 이름 바꾸기가 아닌 복사가 되고, 그러면 돌고 있는 바이너리를 덮어쓰는 문제가 되살아난다.
    DOWNLOAD_PATH="${install_dir}/.kyu.download.$$"

    report "대상: ${target_platform}"
    resolve_release_access

    asset_url=$(asset_api_url_for "$RELEASE_JSON_PATH" "$asset_name")
    if [ -z "$asset_url" ]; then
        fail "최신 릴리스에 ${asset_name} 자산이 없습니다." \
             "릴리스가 이 플랫폼 바이너리를 함께 올렸는지 확인하세요."
    fi

    report "받는 중: ${asset_name}"
    download_asset "$asset_url" "$destination_path"

    report "설치 완료: ${destination_path}"
    warn_if_not_on_path "$install_dir"

    if ! "$destination_path" version; then
        fail "설치한 바이너리를 실행하지 못했습니다: ${destination_path}"
    fi
}

main "$@"
