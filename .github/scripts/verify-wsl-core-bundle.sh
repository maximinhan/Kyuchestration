#!/usr/bin/env bash
#
# msi 에 실을 WSL 코어 묶음(desktop/core 의 packageWslCoreBundle)을 풀어, 윈도우 화면이 WSL 에서 할 일을 그대로 해 본다
# (core-ui-split-design.md 6.17 의 4a 정정). 릴리스 잡과 Desktop CI 가 같은 것을 본다 — 둘이 각자 적으면 한쪽만 낡는다.
#
#   verify-wsl-core-bundle.sh <묶음 tar.gz> <판 라벨(v0.16.0)> <엔진 판: same-label | any>
#
# 엔진 판을 any 로 받는 까닭: PR 의 CI 가 만드는 kyu 에는 태그가 없어 커밋으로 자기를 말한다. 릴리스는 같은 라벨이어야 한다.
#
# 네트워크 없이 설치되는지를 가짜 curl 로 본다 — PATH 맨 앞의 curl 이 불리면 기록이 남고 이 스크립트가 실패한다.

set -euo pipefail

bundle="$1"
expected_label="$2"
engine_version_check="$3"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

ls -l "$bundle"
mkdir "$work/bundle"
tar -xzf "$bundle" -C "$work/bundle"
tar -tzvf "$bundle"

for entry in core.tar.gz kyu install.sh VERSION; do
  test -f "$work/bundle/$entry" || { echo "::error::묶음에 $entry 가 없다"; exit 1; }
done
for executable in kyu install.sh; do
  test -x "$work/bundle/$executable" || { echo "::error::묶음의 $executable 에 실행 권한이 없다"; exit 1; }
done

bundle_label=$(cat "$work/bundle/VERSION")
if [ "$bundle_label" != "$expected_label" ]; then
  echo "::error::묶음의 VERSION 이 '$bundle_label' 다 — 기대 '$expected_label'"
  exit 1
fi

engine_answer=$("$work/bundle/kyu" version)
echo "kyu version: $engine_answer"
case "$engine_version_check" in
  same-label)
    # kyu 는 "kyu v0.16.0 (508ea4dbe759)" 처럼 판 뒤에 빌드 커밋을 덧붙인다(internal/cli/version.go). 견줄 것은 둘째 낱말인 판뿐이다 —
    # v0.16.0 릴리스가 이 줄을 통째로 견줘 멈췄다.
    engine_label=$(printf '%s\n' "$engine_answer" | awk '{print $2}')
    if [ "$engine_label" != "$expected_label" ]; then
      echo "::error::묶음의 kyu 가 '$engine_answer' 로 답한다 — 기대 판 '$expected_label'"
      exit 1
    fi
    ;;
  any) ;;
  *) echo "::error::엔진 판 확인은 same-label 또는 any: $engine_version_check"; exit 1 ;;
esac

mkdir "$work/home" "$work/fake-bin"
printf '#!/bin/sh\necho "$*" >> "%s"\nexit 1\n' "$work/curl-calls.log" > "$work/fake-bin/curl"
chmod +x "$work/fake-bin/curl"

# 4b 의 화면이 wsl.exe 너머에서 부를 모양 그대로다. 환경을 비워 러너의 GITHUB_TOKEN · XDG_* 가 끼어들지 않게 한다.
env -i HOME="$work/home" PATH="$work/fake-bin:/usr/bin:/bin" \
  KYU_CORE_ARCHIVE="$work/bundle/core.tar.gz" KYU_BINARY_FILE="$work/bundle/kyu" \
  bash "$work/bundle/install.sh" --with-core

if [ -e "$work/curl-calls.log" ]; then
  echo "::error::묶음만으로 설치하는데 curl 을 불렀다:"
  cat "$work/curl-calls.log"
  exit 1
fi

installed_label=$(cat "$work/home/.local/share/kyuchestration/core/VERSION")
if [ "$installed_label" != "$expected_label" ]; then
  echo "::error::깔린 코어의 VERSION 이 '$installed_label' 다 — 기대 '$expected_label'"
  exit 1
fi
test -x "$work/home/.local/bin/kyu" || { echo "::error::~/.local/bin/kyu 가 없다"; exit 1; }
test -L "$work/home/.local/bin/kyuchestration-core" || { echo "::error::~/.local/bin/kyuchestration-core 링크가 없다"; exit 1; }
# jspawnhelper 가 실행되지 않으면 코어가 claude · kyu 를 띄우지 못한다 — 인사만으로는 드러나지 않는다.
for executable in bin/kyuchestration-core runtime/bin/java runtime/lib/jspawnhelper; do
  test -x "$work/home/.local/share/kyuchestration/core/$executable" \
    || { echo "::error::깔린 코어의 $executable 에 실행 권한이 없다"; exit 1; }
done

# 화면이 붙는 길 그대로 — 링크로 띄워 인사한다. welcome 의 coreVersion 이 묶음의 VERSION 과 같아야 한다(화면이 견주는 두 값).
printf '%s\n' '{"frame":"hello","protocol":2,"screenVersion":"ci","screenOs":"linux"}' \
  | env -i HOME="$work/home" PATH="/usr/bin:/bin" "$work/home/.local/bin/kyuchestration-core" serve --stdio > "$work/frames.txt"
head -c 300 "$work/frames.txt"; echo
grep -q "\"coreVersion\":\"${expected_label}\"" "$work/frames.txt" \
  || { echo "::error::깔린 코어의 welcome 에 coreVersion ${expected_label} 이 없다"; exit 1; }

echo "WSL 코어 묶음 확인 — 판 ${expected_label}, 네트워크 없이 설치, 코어가 인사한다"
