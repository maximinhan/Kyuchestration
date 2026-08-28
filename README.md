# Kyuchestration

**뀨케스트레이션** — "뀨" + 오케스트레이션.

여러 레포가 하나의 목표로 묶여 움직일 때, 각 레포의 독립성을 유지한 채 조율하는 CLI 도구 `kyu`.

명령 이름 `kyu` 는 프로젝트 이름에서 땄다 — 뀨케스트레이션의 앞 글자다.

- 설계: [workdir-orchestrator-design.md](workdir-orchestrator-design.md)
- 핵심 원칙: **프로세스는 격리, 지식은 공유**
- 스택: Go + tmux 백엔드

## 이 도구가 하는 일

MSA 환경에서 기능 하나가 여러 레포에 걸치면, 작업 단위마다 **워크디렉토리**를 만들고 그 아래에
필요한 레포를 클론해서 쓴다.

```
WorkDir-featureX/
    .coord/plan.md   # 조율용 계획 (이 도구가 만든다)
    proj-a/          # git clone
    proj-b/          # git clone
    proj-c/          # git clone
```

각 레포에서 세션을 따로 띄워야 그 레포의 `.mcp.json`·`CLAUDE.md`·에이전트 설정이 살아난다.
`kyu` 는 그 세션들을 한 화면에서 띄우고, 보고, 오가게 한다.

## 요구사항

| 프로그램 | 쓰이는 곳 | 없으면 |
|---|---|---|
| tmux | 세션 생성·진입·종료 | `kyu init` 외의 명령이 안내와 함께 종료 |
| git | 레포 발견 · 상태 판정 · `kyu clone` 의 클론 | 상태를 판정하지 못하고 클론도 못 함 |
| claude CLI | 세션 안에서 실행되는 명령 | 세션은 뜨지만 바로 끝남 |
| Go | 소스에서 설치할 때만 (버전은 `go.mod` 의 `go` 지시자를 따른다) | 릴리스 바이너리를 받는다 (설치 방식 A·B) |

macOS 와 Windows(WSL) 에서 쓴다. tmux 는 자동 설치하지 않는다 — `brew install tmux` 또는
`sudo apt install tmux`.

## 설치

네 가지 길이 있다. 이 저장소는 아직 **프라이빗**이라 어느 길이든 GitHub 인증이 한 번은 필요하다.

| 방식 | 언제 쓰나 | 미리 있어야 하는 것 |
|---|---|---|
| [A. 설치 스크립트](#a-설치-스크립트-추천) | 새 머신 — 가장 빠르다 | `curl` |
| [B. gh 로 내려받기](#b-gh-로-내려받기) | `gh` 에 이미 로그인한 머신 | `gh` |
| [C. go install](#c-go-install) | Go 가 있는 개발 머신 | Go + git 인증 |
| [D. 소스 빌드](#d-소스-빌드) | 이 저장소를 고치는 중 | Go, git |

### A. 설치 스크립트 (추천)

Go 도 git 도 `gh` 도 필요 없다. 이미 있는 `gh` 인증을 건드리지도 않는다.

**1. fine-grained personal access token 을 발급한다**

Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token

| 항목 | 값 |
|---|---|
| Repository access | Only select repositories → `maximinhan/Kyuchestration` |
| Repository permissions | Contents: **Read-only** |
| Expiration | 짧게 — 필요할 때 다시 발급한다 |

읽기 권한 하나로 끝난다. 토큰이 할 수 있는 일을 이만큼으로 묶어 두면, 새어 나가도 잃는 것이 이만큼이다.

**2. 실행한다**

```sh
export GITHUB_TOKEN=<발급한 토큰>
curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github.raw" \
  https://api.github.com/repos/maximinhan/Kyuchestration/contents/install.sh | bash
```

설치 스크립트 자체도 프라이빗 저장소 안에 있어서 `raw.githubusercontent.com` 으로는 받지 못한다.
그래서 파일 하나를 토큰으로 읽는 API 를 쓴다 — `Accept: application/vnd.github.raw` 가 "JSON 말고
파일 내용 그대로" 라는 뜻이다.

스크립트는 `uname` 으로 플랫폼을 가려 최신 릴리스에서 맞는 바이너리를 `~/.local/bin/kyu` 에 놓는다.
**업데이트도 같은 명령을 다시 실행하면 된다** — 항상 최신 릴리스를 받아 기존 바이너리를 덮어쓴다.
토큰이 만료 전이면 그대로 쓰고, 몇 버전인지는 `kyu version` 으로 확인한다.

자리를 바꾸려면 먼저 내보낸다.

```sh
export KYU_INSTALL_DIR=/usr/local/bin
```

`~/.local/bin` 이 PATH 에 없으면 스크립트가 알려 준다. 셸 설정에 넣는다.

```sh
export PATH="$HOME/.local/bin:$PATH"
```

> 이 저장소를 공개로 바꾸면 `Authorization` 헤더 줄만 빼고 같은 명령이 그대로 동작한다.
> 스크립트가 무인증으로 먼저 물어보고, 거절당했을 때에만 토큰을 꺼내기 때문이다.

### B. gh 로 내려받기

`gh` 에 이미 로그인해 둔 머신이면 세 줄이다.

```sh
mkdir -p ~/.local/bin
gh release download -R maximinhan/Kyuchestration \
  --pattern 'kyu_linux_amd64' -O ~/.local/bin/kyu
chmod +x ~/.local/bin/kyu
```

macOS 는 패턴만 바꾼다 — Apple Silicon 은 `kyu_darwin_arm64`, Intel 은 `kyu_darwin_amd64`.

읽기만 하므로 이미 해 둔 `gh` 인증에 아무 영향이 없다. 다만 그 머신의 `gh` 가 다른 계정으로
로그인돼 있으면 이 저장소를 보지 못한다. 그때 계정을 바꾸는 대신 방식 A 를 쓴다 — A 는 `gh` 를
아예 지나가지 않는다.

### C. go install

Go 가 있는 머신에서 클론 없이 설치한다. 프라이빗 저장소라 두 가지를 먼저 맞춘다.

```sh
# 1. 모듈 프록시와 체크섬 DB 를 지나가지 않게 한다. 둘 다 이 모듈을 볼 수 없어서,
#    그대로 두면 설치가 프록시 단계에서 404 로 끝난다.
go env -w GOPRIVATE='github.com/maximinhan/*'

# 2. go 가 쓰는 git HTTPS 자격증명을 gh 인증에 연결한다
gh auth setup-git
```

`gh` 를 쓰지 않는다면 2번 대신 토큰을 git 에 물린다.

```sh
git config --global url."https://<토큰>@github.com/".insteadOf "https://github.com/"
```

이 방법은 토큰이 `~/.gitconfig` 에 평문으로 남는다. 만료를 짧게 잡고, 다 쓰면 지운다.

```sh
git config --global --unset url."https://<토큰>@github.com/".insteadOf
```

그다음.

```sh
go install github.com/maximinhan/Kyuchestration/cmd/kyu@latest
```

`$(go env GOPATH)/bin/kyu` 에 설치되므로 그 디렉토리가 PATH 에 있어야 한다.

이 경로로 받은 바이너리에는 릴리스 빌드의 버전 주입이 없다. 대신 `kyu version` 이 모듈 버전을
답한다 — 태그가 있으면 `v0.1.0`, 없으면 커밋이 박힌 유사 버전이다.

### D. 소스 빌드

이 저장소를 고치는 중이라면 이것이다.

```sh
git clone https://github.com/maximinhan/Kyuchestration.git
cd Kyuchestration
go build ./cmd/kyu          # 현재 디렉토리에 kyu 바이너리가 생긴다
mv kyu ~/.local/bin/        # PATH 에 들어있는 아무 디렉토리
```

## 빠른 시작

```sh
mkdir -p ~/work/WorkDir-featureX && cd ~/work/WorkDir-featureX
kyu clone   # GitHub 목록에서 화살표로 골라 클론 (손으로 git clone 해도 된다)
kyu
```

`kyu` 한 번이 세 가지를 순서대로 한다.

1. `.coord/plan.md` 가 없으면 만든다 (`kyu init` 과 같은 것)
2. 메인 세션이 없으면 워크디렉토리 최상위에서 띄운다 (`kyu start` 와 같은 것 — 클론해둔 레포가
   `--add-dir` 로 붙는다)
3. 그 메인 세션에 들어간다 (`kyu attach main` 과 같은 것)

**빠져나올 때는 `Ctrl-b d`** — `Ctrl` 과 `b` 를 함께 누르고 뗀 다음 `d` 를 누른다. 세션은 살아있고
터미널만 빠져나온다. 다시 `kyu` 를 치면 같은 세션으로 돌아온다. 몇 번을 쳐도 결과가 같다.

레포를 클론하기 전에 `kyu` 를 쳐도 된다 — 무엇을 클론할지 정하는 것부터가 메인 세션이 할 일이다.
다만 `--add-dir` 목록은 세션을 만드는 순간 굳으므로, 세션 안에서 클론한 레포까지 붙이려면
`kyu kill main` 뒤에 `kyu` 를 다시 실행한다.

> 홈 디렉토리(`$HOME`)와 파일시스템 루트에서는 자동 초기화를 거절한다. `cd` 를 한 번 빠뜨린
> 실행이 홈 전체를 워크디렉토리로 만들면 홈 아래의 모든 레포가 메인 세션에 붙고 `.coord/plan.md`
> 가 홈에 남는다. 정말 그 자리를 워크디렉토리로 쓰려면 `kyu init` 을 직접 실행한다.

지금 상태만 보고 싶을 때는 `kyu list` 다.

## 처음부터 끝까지 한 번

레포 두 개를 함께 고치는 작업을 예로 든다. 위의 `kyu` 한 번이 하는 일을 단계로 나누고, 레포마다
세션을 따로 띄우는 데까지 이어간다.

**1. 워크디렉토리를 만든다**

```sh
cd ~/work
kyu init WorkDir-featureX
cd WorkDir-featureX
```

`.coord/plan.md` 가 만들어진다. 이름을 주지 않고 `kyu init` 만 실행하면 현재 디렉토리가
워크디렉토리가 된다. **이미 계획 파일이 있으면 덮어쓰지 않고 거절한다** — 사람이 쓴 계획을
도구가 지우지 않는다.

**2. 레포를 클론한다**

```sh
kyu clone
```

GitHub 목록에서 화살표로 고른다(아래 [`kyu clone`](#kyu-clone)). 손으로 해도 결과는 같다.

```sh
git clone <proj-a 주소> proj-a
git clone <proj-b 주소> proj-b
```

설정 파일에 레포 목록을 적을 필요는 없다. 클론하면 곧바로 인식된다.

**3. 지금 상태를 본다**

```sh
kyu list
```

```
WorkDir: WorkDir-featureX   (2 repos, 0 sessions)

  ○  proj-a  IDLE
  ○  proj-b  IDLE
  ○  main    IDLE

kyu attach <repo> 로 진입
```

**4. 레포 세션을 띄운다**

```sh
kyu start proj-a
```

`proj-a` 디렉토리에서 세션이 뜬다. 그 레포의 MCP 설정·지침·에이전트가 그대로 로드된다.

**5. 세션에 들어간다**

```sh
kyu attach proj-a
```

**빠져나올 때는 `Ctrl-b d`** — `Ctrl` 과 `b` 를 함께 누르고 뗀 다음 `d` 를 누른다.
세션은 살아있고 터미널만 빠져나온다. tmux 에서 알아야 할 것은 이 키 하나뿐이다.

> `Ctrl-b d` 대신 창을 닫아도 세션은 살아있다. 다시 `kyu attach proj-a` 로 들어가면 된다.
> 세션을 진짜로 끝내려면 `kyu kill` 을 쓴다.

**6. 계획을 적는다**

`.coord/plan.md` 를 열어 frontmatter 의 예시 주석을 풀고 내용을 바꿔 쓴다.

```markdown
---
tasks:
  - id: commons-event
    repo: proj-a
    title: 이벤트 클래스 추가
    needs: []
    status: done

  - id: publish
    repo: proj-b
    title: 발행 로직 구현
    needs: [commons-event]
    status: doing
---
```

이제 `kyu list` 가 작업까지 함께 보여준다.

```
WorkDir: WorkDir-featureX   (2 repos, 1 session)

  ●  proj-a  RUNNING  [commons-event] done
  ○  proj-b  DIRTY    [publish] doing
  ○  main    IDLE

kyu attach <repo> 로 진입
```

**7. 정리한다**

```sh
kyu kill --all
```

## 명령

```
kyu                    이 디렉토리에서 작업 시작 — 초기화·메인 세션 생성·진입까지 한 번에

kyu init [name]        워크디렉토리 초기화 (.coord/plan.md 생성)
kyu clone              GitHub 레포 목록에서 화살표로 골라 이 디렉토리에 클론
kyu list [path]        레포 목록 + 상태
kyu start [repo]       세션 시작. 인자 없으면 main
kyu attach <repo>      세션 진입. main 도 가능
kyu kill [repo|--all]  세션 종료
kyu auth <list|remove> 저장한 GitHub 토큰 프로필 관리
kyu version            이 바이너리의 버전

옵션 (kyu, kyu start):
  --bypass-permissions   claude 를 권한 확인 없이 띄운다 — 신뢰하는 워크디렉토리에서만
  --repo-claude-md       메인 세션이 각 레포의 CLAUDE.md 까지 읽는다 (kyu start 전용)
```

### `kyu`

인자 없이 실행하면 초기화 → 메인 세션 생성 → 진입을 한 번에 한다. 각 걸음은 이미 있는 것이면
건너뛴다 — 계획 파일이 있으면 만들지 않고, 세션이 떠 있으면 만들지 않고 붙기만 한다.

`kyu --bypass-permissions` 는 메인 세션의 claude 를 권한 확인 없이 띄운다
([아래](#--bypass-permissions)). 이미 세션이 떠 있으면 그 세션에는 적용되지 않는다 —
세션이 실행할 명령은 만드는 순간 정해지기 때문이다.

이미 세션 안에서 실행하면 거절한다. `Ctrl-b d` 로 빠져나온 뒤 다시 실행한다.

홈 디렉토리와 파일시스템 루트에서는 자동 초기화를 거절한다(위 [빠른 시작](#빠른-시작) 참고).
이미 `kyu init` 으로 초기화해둔 자리라면 홈이라도 그대로 진입한다 — 거절하는 것은 자동 초기화뿐이다.

### `kyu init [name]`

`.coord/plan.md` 템플릿을 만든다. 이름을 주면 그 디렉토리를 만들어(이미 있으면 그대로 쓴다)
그 안을 초기화하고, 이름이 없으면 현재 디렉토리를 초기화한다.

계획 파일이 이미 있으면 아무것도 하지 않고 종료 코드 1 로 끝난다. 새로 만들려면 그 파일을
직접 지우거나 옮긴다.

이 명령만 tmux 없이 동작한다.

### `kyu clone`

```sh
kyu clone
```

GitHub 레포 목록에서 화살표로 골라 **현재 디렉토리에** 클론한다. 워크디렉토리는 필요한 레포를
클론해서 쓰는 자리인데(설계 문서 1.1) 그 클론만은 손으로 하고 있었다 — 주소를 하나씩 조립하는
일이라 오타가 나고, "이 계정에 어떤 레포가 있더라" 를 GitHub 화면에서 확인하고 돌아와야 한다.

진입 플로우(`kyu`)에 끼워 넣지 않았다. 클론은 워크디렉토리를 만들 때 한 번 하는 일이고, 매번
진입할 때마다 GitHub 에 붙어 목록을 묻는 것은 원한 적 없는 왕복이다.

흐름은 네 걸음이다.

1. **토큰 프로필을 고른다** — 등록된 것이 없으면 그 자리에서 등록한다 ([아래](#kyu-clone-의-토큰))
2. **소유자를 고른다** — 개인 계정과 소속 조직 중에서. 조직이 없으면 묻지 않는다
3. **목록에서 고른다** — 화살표로 옮기고 스페이스로 켜고 엔터로 확정한다 (키는 [아래 표](#kyu-clone-의-키))
4. **클론한다** — 하나가 실패해도 나머지는 계속하고, 마지막에 실패한 이름을 모아 알린다

```
 maximinhan 의 레포 28 개 — 최근 갱신 순

❯ [x] Kyuchestration     private  2026-08-27
  [ ] my-bit                      2026-08-26  (이미 있음)
  [x] photo-identify              2026-08-25
  [ ] proj-alpha         private  2026-08-24
  ...

  ••

  ↑/k 위 • ↓/j 아래 • / 검색 • space 선택 • enter 클론 • q 취소 • ? 더 보기
```

목록 순서는 **github.com 의 repositories 탭과 같다**(최근 갱신 순). 사용자가 "어떤 레포가
있더라" 를 확인하는 곳이 그 화면이라, 거기서 위에 있던 레포가 여기서도 위에 있어야 목록을
처음부터 다시 훑지 않는다.

한 화면에 담기는 만큼만 보여주고 나머지는 쪽으로 넘긴다. 아래의 `••` 이 지금 몇 쪽인지다.

#### `kyu clone` 의 키

| 키 | 하는 일 |
|---|---|
| `↑` `↓` / `k` `j` | 커서를 위아래로 옮긴다 |
| `←` `→` / `h` `l` | 이전 쪽 · 다음 쪽 |
| `g` / `G` | 목록의 처음 · 끝 |
| `space` | 커서가 있는 레포를 골랐다 껐다 한다 (`[x]` / `[ ]`) |
| `enter` | 확정하고 클론한다. **고른 것이 없으면 커서가 있는 하나만** |
| `/` | 목록 안에서 이름으로 검색. 치는 대로 좁혀진다 |
| `esc` | 검색이 걸려 있으면 그 검색을 푼다. 아니면 취소하고 나간다 |
| `q` | 취소하고 나간다 |
| `?` | 도움말을 펼친다 |

검색 중에는 `space` · `q` · `enter` 가 그대로 검색어로 들어간다. 그때의 스페이스는 검색어의
공백이고 `q` 는 레포 이름의 글자이며 엔터는 검색어 확정이다 — 클론까지 가려면 검색어를 확정한
뒤에 엔터를 한 번 더 누른다.

같은 이름의 디렉토리가 이미 있으면 그 줄이 흐려지고 `(이미 있음)` 이 붙는다. 골라도 건너뛴다 —
`git clone` 은 그 자리에서 실패하는데, 그 실패를 넘기는 대신 목록에서 미리 알린다.

클론이 끝나면 **이미 떠 있는 메인 세션에는 새 레포가 붙지 않는다**고 알린다. 세션의 `--add-dir`
목록은 만드는 순간 굳기 때문이다 — `kyu kill main` 뒤에 `kyu` 를 다시 실행하면 반영된다.

목록을 보고 그만두는 것은 실패가 아니다. `q` 나 `esc` 로 나가면 종료 코드 0 으로 끝난다.

#### 터미널이 아닐 때

입력이나 출력이 터미널이 아니면(파이프·스크립트) 이 화면을 그리지 않고 **번호를 묻는다**.
화면을 그리려면 커서를 옮기는 제어 문자를 내보내고 키를 한 글자씩 받아야 하는데, 파이프에는
그럴 상대가 없고 그 제어 문자는 기록에 그대로 섞인다.

```
maximinhan 의 레포 3 개 — 최근 갱신 순

  1)  Kyuchestration  private  2026-08-27  (이미 있음)
  2)  proj-a                   2026-08-20
  3)  proj-b          private  2026-08-11

클론할 레포 (1,3,5-8 / 빈 줄이면 취소): 2,3

  proj-a 클론 완료
  proj-b 클론 완료

클론 2 개
```

번호는 `1,3,5-8` 처럼 쉼표와 범위를 섞어 적는다. 빈 줄이면 취소다.

#### `kyu clone` 의 토큰

**머신에 굴러다니는 인증을 읽지 않는다.** `GITHUB_TOKEN` 환경변수도, `gh` 의 로그인도 쓰지 않는다.
그런 것을 집어 쓰면 회사 토큰이 깔린 머신에서 개인 레포를 클론하려던 사람이 엉뚱한 계정의 목록을
보게 되고, 무엇으로 인증했는지 화면에서 확인할 방법도 없다. 쓰는 것은 직접 등록한 토큰뿐이고,
어느 프로필로 붙었는지는 매번 화면에 찍힌다.

처음 실행하면 그 자리에서 등록을 물어본다. 토큰은 저장하기 전에 GitHub 에 한 번 확인하고,
거절당하면 저장하지 않고 다시 묻는다.

```
프로필 이름 (예: 개인, 회사): 개인
GitHub personal access token (입력은 보이지 않습니다):
토큰 확인 완료 — maximinhan 계정입니다.
토큰을 저장했습니다: 개인 (macOS 키체인)
```

필요한 권한은 **레포 목록 조회와 클론**이다.

| 토큰 종류 | 필요한 권한 |
|---|---|
| 클래식 | `repo` — 비공개 레포를 목록에 띄우고 클론하는 데 필요하다 |
| fine-grained | Repository permissions → **Contents: Read**. 조직 레포까지 보려면 그 조직을 토큰의 대상(Resource owner)에 포함한다 |

> 설치 방식 [A](#a-설치-스크립트-추천) 의 토큰과 다르다. 그쪽은 릴리스 자산을 내려받는 최소
> 토큰이라 이 목록을 볼 권한이 없다. 같은 토큰을 쓰고 싶다면 권한을 넓혀 발급한다.

토큰은 이 머신에만 저장된다. 저장 자리는 순서대로 고른다.

| 자리 | 언제 |
|---|---|
| macOS 키체인 | macOS (`security`) |
| secret-service (libsecret) | 리눅스 데스크톱 — `secret-tool` 이 실제로 응답할 때 |
| 설정 파일 (평문, 권한 0600) | 위 둘이 없을 때 (WSL·데스크톱 없는 SSH 등) |

마지막 폴백은 **평문**이다. 감추는 대신 드러낸다 — 토큰을 받기 전에 경고를 내고, `kyu auth list`
에도 저장 위치가 계속 보인다. 토큰을 디스크에 남기고 싶지 않다면 그 자리에서 중단한다.

```
경고: 이 머신에는 키체인도 secret-service 도 없어 토큰을 설정 파일에 평문으로 저장합니다 (권한 0600).
      토큰을 남기고 싶지 않다면 지금 중단하고(Ctrl-C), 키체인이 있는 머신에서 실행하세요.
```

프로필 이름 목록은 `$XDG_CONFIG_HOME/kyu/profiles.json`(macOS 는 `~/Library/Application Support/kyu/`)
에 남는다. **이름만 적힌다** — 키체인·secret-service 를 쓰는 머신에서는 토큰 값이 어느 파일에도
들어가지 않는다.

**클론에도 토큰이 디스크에 남지 않는다.** 이번 실행에만 사는 credential helper 로 흘린다.

```sh
git -c credential.helper= \
    -c 'credential.helper=!f() { echo username=x-access-token; echo "password=$KYU_CLONE_TOKEN"; }; f' \
    clone https://github.com/maximinhan/proj-a.git
```

앞의 빈 `credential.helper=` 가 전역 helper(`store` 등)를 이번 실행에서만 끄고, 토큰은 인자가 아니라
자식 프로세스의 환경변수로 넘어간다 — 인자는 같은 머신의 다른 사용자가 `ps` 로 읽을 수 있다.
클론된 `.git/config` 에는 깨끗한 `https://github.com/...` 주소만 남는다. 주소에 토큰을 끼워 넣는
방법(`https://x-access-token:<토큰>@github.com/...`)을 쓰지 않는 이유가 이것이다.

### `kyu list [path]`

경로를 주면 그 워크디렉토리를 본다. 인자 없는 `kyu` 는 목록이 아니라 진입이므로, 목록은
`kyu list` 로 명시해서 본다.

```
WorkDir: WorkDir-featureX   (3 repos, 2 sessions)

  ●  proj-a  RUNNING  [commons-schema] doing (done 1/2)
  ○  proj-b  DIRTY    [publish] ready
  ○  proj-c  IDLE     [consume] blocked ← needs commons-schema
  ●  main    RUNNING

kyu attach <repo> 로 진입
```

왼쪽 동그라미는 세션 생존이다. `●` 는 세션이 떠 있고 `○` 는 없다.

상태는 도구가 tmux 와 git 에게 물어 매번 다시 계산한다. 세션이 자기 상태를 보고하지 않는다.

| 상태 | 뜻 |
|---|---|
| `RUNNING` | 세션이 살아있다 |
| `DIRTY` | 세션 없음 + 커밋하지 않은 변경이 있다 |
| `AHEAD` | 세션 없음 + 워킹 트리는 깨끗한데 푸시하지 않은 커밋이 있다 |
| `IDLE` | 세션도 없고 넘길 것도 없다 |

오른쪽 칸은 `.coord/plan.md` 에 적어둔 작업이다. 한 레포에 작업이 여럿이면 **지금 볼 작업**
하나만 보여준다 — `doing` → `ready` → `blocked` 순으로 고르고, 같은 상태면 계획에 먼저 적힌
것이다. 뒤의 `(done 1/2)` 는 그 레포 전체 진척이고, 전부 끝났으면 `done 2/2` 로만 나온다.
`blocked` 인 작업에는 아직 끝나지 않은 선행이 `← needs` 로 붙는다.

계획을 읽지 못했거나 없는 레포를 가리키는 작업이 있으면 **경고를 stderr 로** 내고 목록은 그대로
보여준다. 계획이 깨져도 도구 전체가 멈추지는 않는다.

### `kyu start [repo]`

```sh
kyu start proj-a                       # proj-a 디렉토리에서 세션을 띄운다
kyu start                              # 워크디렉토리 최상위에서 메인 세션을 띄운다
kyu start --repo-claude-md             # 메인 세션이 각 레포의 CLAUDE.md 까지 읽게 한다
kyu start proj-a --bypass-permissions  # 권한 확인 없이 띄운다 (아래 경고를 먼저 읽을 것)
```

**레포 세션**은 그 레포 디렉토리에서 뜬다. 설정은 오직 세션을 시작한 디렉토리에서만 오기 때문에,
이 한 가지로 그 레포의 `.mcp.json`·`CLAUDE.md`·`.claude/agents/`·훅이 전부 살아난다.

**메인 세션**은 워크디렉토리 최상위에서 뜨고, 하위 레포를 `--add-dir` 로 자동으로 붙인다.
목록을 손으로 관리하지 않는다 — 클론된 레포를 매번 다시 스캔해서 조립한다.

```sh
claude --add-dir /abs/path/WorkDir-featureX/proj-a --add-dir /abs/path/WorkDir-featureX/proj-b
```

메인은 각 레포를 읽어 현황을 파악하고, 인터페이스를 확정하고, 작업 순서를 `.coord/plan.md` 에
적는 자리다. 코드는 직접 고치지 않는다. `--add-dir` 은 **파일 접근만 주고 설정은 주지 않기**
때문에 이 용도에 정확히 맞는다.

`--repo-claude-md` 는 그 예외를 연다. 이 옵션을 주면 메인 세션이 추가 디렉토리의 `CLAUDE.md` 와
`.claude/rules/` 까지 읽는다(`CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`). 붙인 레포 수만큼
컨텍스트가 늘어나므로 기본은 꺼져 있다. 계획이 레포 컨벤션과 어긋나는 문제가 실제로 생겼을 때 켠다.
레포 세션은 자기 디렉토리의 `CLAUDE.md` 를 이미 읽으므로 이 옵션을 함께 줄 수 없다.

이미 떠 있는 세션에 `kyu start` 를 다시 실행해도 실패하지 않는다. 안내만 하고 끝난다.

### `--bypass-permissions`

```sh
kyu --bypass-permissions               # 메인 세션을 권한 확인 없이 띄우고 진입한다
kyu start --bypass-permissions         # 메인 세션만 띄운다
kyu start proj-a --bypass-permissions  # 그 레포의 세션을 띄운다
```

세션의 `claude` 를 `--dangerously-skip-permissions` 와 함께 실행한다. 메인 세션과 레포 세션
모두에서 쓸 수 있다 — 권한 확인은 세션이 어느 디렉토리에서 떴는지와 무관하기 때문이다.

```sh
claude --dangerously-skip-permissions --add-dir /abs/path/WorkDir-featureX/proj-a
```

> **경고.** 이 옵션으로 띄운 세션은 **확인 프롬프트 없이 파일을 고치고 명령을 실행한다.**
> 레포 세션이면 그 레포 전체가, 메인 세션이면 `--add-dir` 로 붙은 모든 레포가 그 대상이다.
> 내용을 신뢰하는 워크디렉토리에서만 쓴다.

기본값으로 저장하는 길은 두지 않았다. 위험한 모드는 실행할 때마다 손으로 켜는 것이라,
설정 파일에 한 번 적어두고 잊는 자리를 만들지 않는다.

세션이 실행할 명령은 세션을 만드는 순간 정해지고 그 뒤로 바뀌지 않는다. 이미 떠 있는 세션에
이 옵션을 다시 줘도 아무것도 바뀌지 않으므로, 그 사실을 stderr 로 알린다.

```
--bypass-permissions 는 이미 실행 중인 main 세션에는 적용되지 않습니다 — 세션이 실행할 명령은 만들 때 정해집니다.
이 옵션으로 다시 띄우려면 kyu kill main 뒤에 다시 실행하세요.
```

`kyu` 는 그 경고를 내고도 진입까지 이어간다. 사용자가 원한 것은 그 워크디렉토리에서 작업을
시작하는 것이고, 떠 있는 세션이 그 요구를 이미 채우고 있다.

### `kyu attach <repo>`

```sh
kyu attach proj-a
kyu attach main
```

**빠져나오기는 `Ctrl-b d`.** 진입 직전에 이 안내가 한 줄 찍힌다.

이미 세션 안에서 실행하면 거절한다 — 세션 안의 세션이 되면 어느 쪽에서 빠져나오는 것인지
알 수 없다.

### `kyu kill [repo|--all]`

```sh
kyu kill proj-a   # 하나만
kyu kill main     # 메인 세션
kyu kill --all    # 이 워크디렉토리의 세션 전부
```

`--all` 은 이 워크디렉토리의 세션만 종료한다. 다른 워크디렉토리의 세션과 직접 띄운 tmux 세션은
건드리지 않는다. 인자 없는 `kyu kill` 을 "전부"로 해석하지 않는다 — 이름을 빠뜨린 한 번으로
다른 레포의 작업까지 날아가지 않게 한다.

### `kyu auth <list|remove>`

```sh
kyu auth list            # 등록된 토큰 프로필과 저장 위치
kyu auth remove 회사     # 프로필과 그 토큰을 지운다
```

```
토큰 프로필 2 개

  개인  macOS 키체인
  회사  설정 파일 (평문, 권한 0600)

지우기: kyu auth remove <이름>
```

**토큰 값은 출력하지 않는다.** 목록을 띄운 채 화면을 공유하거나 캡처하는 일이 흔하고, 한 번 새
나간 토큰은 폐기할 때까지 계속 유효하다. 이 명령은 저장소에 값을 물어보지도 않는다.

등록하는 하위 명령은 두지 않았다. 토큰이 필요한 자리는 `kyu clone` 하나뿐이고, 거기서 물어보는
것이 실제로 지나는 길이다. 등록 명령을 따로 두면 "먼저 등록하고 나서 클론" 이라는 순서를 사람이
기억해야 한다.

저장된 토큰이 만료되면 `kyu clone` 이 그 사실을 알리고 같은 이름으로 다시 등록하게 해준다.
지우고 다시 등록하는 두 걸음을 스스로 찾지 않아도 된다.

`tmux` 없이도 동작한다 — 저장해둔 토큰을 보고 지우는 일은 세션과 무관하다.

### `kyu version`

```sh
kyu version
```

```
kyu v0.1.0 (a1b2c3d4e5f6)
```

릴리스 바이너리는 소스와 떨어져 돌아다닌다. 새 머신에 복사해 둔 `kyu` 가 어느 버전인지 물을
곳이 저장소 쪽에는 없어서, 바이너리 자신이 답한다.

괄호 안은 그 바이너리를 만든 커밋이다. 커밋되지 않은 변경이 섞인 채로 빌드했으면 `+dirty` 가
붙는다 — 그 표시가 없으면 있지도 않은 커밋에서 버그를 찾게 된다.

버전이 어디서 오는지는 설치 방식마다 다르다.

| 설치 방식 | `kyu version` 이 답하는 것 |
|---|---|
| 릴리스 바이너리 (A·B) | 릴리스 태그. 빌드할 때 링커가 박아 넣는다 |
| `go install` (C) | 모듈 버전. 태그가 없으면 커밋이 들어간 유사 버전 |
| 소스 빌드 (D) | 유사 버전 + 커밋 |

`tmux` 없이도 답한다. 릴리스 바이너리는 tmux 를 아직 깔지 않은 새 머신에 먼저 도착한다.

## `.coord/plan.md`

레포 사이에 공유해야 하는 것 — 확정한 인터페이스, 작업 순서, 지금 어디까지 왔는지 — 을 적는 파일이다.
**레포 안에는 아무것도 넣지 않는다.** 레포는 팀 공용이고, 조율에 필요한 것은 전부 워크디렉토리에 둔다.

세션끼리 직접 이야기하지 않는다. 메인이 이 파일에 쓰고 각 레포 세션이 필요할 때 읽는다.
그래서 세션이 언제 뜨고 죽어도 조율 상태가 유실되지 않고, 사람이 직접 열어 고칠 수 있다.

YAML frontmatter 와 Markdown 본문으로 이루어진다. **도구는 frontmatter 만 읽고, 본문은
사람과 세션이 읽는다.**

```markdown
---
tasks:
  - id: commons-event      # 작업 식별자 (필수). 다른 작업의 needs 가 이것을 가리킨다
    repo: proj-a           # 수행할 레포 디렉토리명 (필수)
    title: 이벤트 클래스 추가  # 한 줄 설명 (필수)
    needs: []              # 선행 작업 id 목록. 비었으면 바로 시작 가능
    status: done           # blocked / ready / doing / done (필수)
---

## 확정 인터페이스

다른 레포가 의존하는 시그니처를 그대로 적는다. 요약하지 않는다.

## 배경

이 작업을 하는 이유와 결정 근거.

## 작업 순서 근거

어느 작업이 먼저여야 하는지, 어느 작업이 병렬로 가능한지와 그 근거.
```

`status` 는 사람 또는 세션이 갱신한다. 도구는 읽어서 보여줄 뿐 자동으로 바꾸지 않는다.
선행이 다 끝났으니 `blocked` → `ready` 로 옮기는 자동 전이는 v2 후보다.

`kyu init` 이 만드는 템플릿에는 이 예시가 전부 주석으로 들어 있다. 줄 맨 앞의 `#` 를 지우면
그대로 계획이 된다. 주석뿐인 상태는 "계획 없음" 으로 읽히므로 목록이 오염되지 않는다.

자세한 규약은 [설계 문서 6.1](workdir-orchestrator-design.md#61-planmd) 에 있다.

## 구조

```
cmd/kyu/         # 진입점, 명령 라우팅
internal/
    session/     # SessionBackend 인터페이스 + tmux 구현 (유일한 플랫폼 의존부)
    workdir/     # 스캔 · 상태 추론 · plan 파싱 (플랫폼 무관 핵심)
    github/      # GitHub REST API 조회 + 클론 (kyu clone 이 닿는 유일한 바깥 세계)
    secretstore/ # 토큰 프로필 저장 (키체인 · secret-service · 파일 폴백)
    cli/         # 명령 구현
desktop/         # 데스크톱 앱 (Kotlin + Compose Multiplatform, 독립 Gradle 빌드)
```

`internal/session` 밖에서는 tmux 를 직접 호출하지 않는다. 이 규칙이 지켜지는 한 새 플랫폼
지원은 파일 하나를 더하는 것으로 끝난다.

## 데스크톱 앱 (개발 중)

`desktop/` 은 맥 · 윈도우 · 리눅스에서 같은 코드로 도는 GUI 다. [설계 문서
5.1](workdir-orchestrator-design.md#51-3층-구조)이 표시 계층을 "교체 가능한 껍데기" 라고 부른
바로 그 자리를 터미널 대신 창으로 채운다. 조율 계층은 그대로 두고 `kyu` 를 엔진으로 호출할
예정이므로, 지금 있는 것은 창이 뜨는 골격까지다. 저장소 루트의 Go 모듈과는 완전히 분리된 독립
Gradle 빌드라 서로의 빌드에 끼어들지 않는다.

```sh
cd desktop
./gradlew run    # 창 띄우기
./gradlew build  # 컴파일 + 테스트
```

JDK 21 만 있으면 되고 Gradle 은 설치하지 않아도 된다 — wrapper 가 저장소에 들어 있다.

## 개발

```sh
go test ./...
go build ./cmd/kyu
```

## 릴리스

`v` 로 시작하는 태그를 밀면 [릴리스 워크플로](.github/workflows/release.yml)가 돈다.

```sh
git tag v0.1.0
git push origin v0.1.0
```

워크플로는 검사(`go vet`·`go test`)를 다시 지난 뒤에야 빌드에 닿는다 — 테스트가 깨지면 릴리스가
만들어지기 전에 멈추므로, 자산이 반쯤 붙은 릴리스가 남지 않는다.

`linux/amd64`·`darwin/arm64`·`darwin/amd64` 세 개를 만들어 릴리스에 붙이고, 태그 이름을 그대로
버전으로 박는다. 받은 사람이 `kyu version` 으로 그것을 확인한다.
