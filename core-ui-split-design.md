# 코어와 화면을 가른다 — 윈도우 창, WSL 세션 설계

> 창은 윈도우의 것으로 뜨고, 프로젝트 폴더·`claude` 세션·`kyu` 엔진은 WSL 안에서 돈다. 지금 한 JVM 이 하는 일을 둘로 가른다 — 세션을 소유하는 **코어**(헤드리스 JVM)와 그것을 그리는 **화면**(Compose). 화면은 코어를 자식 프로세스로 띄우고 stdio 로 JSON 줄을 주고받는다. 윈도우에서는 그 자식이 `wsl.exe` 너머에 있고, 맥·리눅스에서는 같은 머신에 있다. 길은 하나다. 화면은 언제 죽어도 되고, 코어는 세션과 함께 남아 다음 화면을 기다린다.

- 작성일: 2026-10-22
- 상태: **설계 확정 전 — 실측 완료, 사용자 승인 대기.** 구현 미착수
- 선행 문서: [app-owned-sessions-design.md](app-owned-sessions-design.md) — 원칙 5(개정)·11·12, 5.5(대화 이어가기)
- 선행 문서: [chat-ui-design.md](chat-ui-design.md) — 5.4(승인 브리지·앱 소켓), 원칙 14·15
- 선행 문서: [session-tree-design.md](session-tree-design.md) — 6.4(앱 소켓의 `kind` 규약), 6.9(관문), 6.13(수명), 원칙 16·17·18
- 전제: **v0.14.0 의 엔진과 앱**(`8c84f52`). 앱 하나(`desktop/`)가 상태 홀더와 Compose 화면을 한 JVM 에 담고, 윈도우 사용자는 WSL 안에 deb 를 깔아 WSLg 창으로 쓴다
- 이 문서가 정하는 것: 코어·화면의 경계(프로토콜), 둘을 잇는 통로, 코어의 수명과 재접속, 경로·OS 기능의 자리, Gradle 모듈과 패키징, 구현 순서

---

## 1. 배경 — 무엇이 바뀌었나

### 1.1 사용자가 정한 것 (2026-10-22)

사용자의 메인 머신은 **윈도우 + WSL2** 다. 지금 앱은 WSL 에 deb 로 깔아 WSLg 창으로 띄우거나, 맥에서 dmg 로 쓴다. 사용자가 원하는 것은 IntelliJ·VS Code 의 원격 개발과 같은 모양이다.

| 원하는 것 | 지금 |
|---|---|
| **창은 윈도우 네이티브** — 작업 표시줄·Alt+Tab·IME·클립보드·고해상도 배율이 윈도우의 것 | WSLg 창. 리눅스 앱이 RDP 너머로 그려진다 |
| **프로젝트 폴더·`claude`·`kyu` 는 WSL 안** — 리눅스 파일 시스템의 속도, 리눅스용 도구 체인 | 그렇다. 앱 전체가 WSL 안에 있으므로 |

세 안이 놓였고 사용자는 **3 번 코어+화면 분리** 를 골랐다. 1 번(앱이 `wsl.exe` 로 프로세스를 감싸는 WSL 백엔드 모드)과 2 번(WSLg 유지)은 기각했다 — 사유는 4 절이다.

### 1.2 지금의 모양 — 한 JVM 이 다 한다

조립은 `desktop/src/main/kotlin/com/kyuchestration/desktop/Main.kt:90` 한 자리다. 상태 홀더 여섯(엔진 설치·claude 인증·대시보드·초기화·레포 클론·챗 세션)을 세우고, 같은 JVM 의 Compose 창이 그 `StateFlow` 를 `collectAsState` 로 읽고 함수를 부른다. 세션의 프로세스(`claude`)·앱 소켓·`kyu` 호출이 모두 그 JVM 의 자식이다.

윈도우를 대상에서 뺀 흔적이 코드 곳곳에 이유와 함께 남아 있다. 이 설계가 그 이유들을 하나씩 걷는다.

| 자리 | 적힌 이유 | 이 설계에서 |
|---|---|---|
| `desktop/build.gradle.kts:412` — *"Msi 는 뺀다"* | 윈도우 엔진 바이너리가 없어 *"설치는 되고 부를 것이 없는 패키지"* | 화면만 윈도우에 깔리고 엔진은 WSL 의 것을 코어가 부른다. msi 는 화면만 담는다(6.16) |
| `.github/workflows/release.yml:121` | 같은 이유 | 윈도우 잡이 하나 는다(7 절 4 단계) |
| `WorkDirDirectoryChooser.kt:21` — *"윈도우 네이티브는 이 앱의 대상이 아니고"* | 맥·리눅스 둘만 가르면 됐다 | 파일 선택기는 화면의 것이고, 윈도우 갈래가 생긴다(6.10) |
| `AppSocketLocation.kt:24` — *"윈도우 갈래를 두지 않는다"* | 그 갈래를 지날 실행이 없다 | **여전히 두지 않는다.** 앱 소켓을 여는 것은 코어고, 코어는 언제나 리눅스·맥에서 돈다 |
| `EngineInstallationFailure.kt:29` — `WindowsNeedsWsl` | 윈도우에서 엔진을 받을 수 없다 | 엔진 설치는 코어의 일이 되고 코어는 WSL 에 있다. 화면 쪽의 같은 자리는 "WSL 에 코어가 없다" 로 바뀐다(6.17) |

### 1.3 정해진 모양

```
┌────────────── 윈도우 ──────────────┐        ┌──────────────────── WSL2 (Ubuntu-24.04) ────────────────────┐
│ [화면] Compose 네이티브 창 (msi)     │        │ [코어] kyuchestration-core (헤드리스 JVM)                       │
│  - 상태를 그린다                     │ stdio  │  - ChatSessionStateHolder · SessionTree · AppSocket           │
│  - 명령을 보낸다                     │◀──────▶│  - ProcessChatSession(claude 보유) · 관문 · 승인               │
│  - 파일 선택기 · 클립보드 · 브라우저   │ JSON 줄 │  - kyucli/ · workdir/ · engine/ · claudeauth/ · repoclone/   │
└────────────────────────────────────┘ wsl.exe └───────────────────────────────────────────────────────────────┘
```

- **코어**: 지금의 `terminal/chat/` 전부와, 엔진·인증·워크디렉토리·클론을 부르는 홀더들. **Go 데몬을 새로 짜지 않는다** — 코틀린 코드를 그대로 옮긴다.
- **화면**: 코어가 주는 상태 변화를 그리고 명령을 보낸다. 윈도우에서는 네이티브 창(msi), 맥·리눅스는 지금처럼.
- **연결**: 화면이 코어를 자식 프로세스로 띄우고 stdio 로 JSON 줄을 주고받는다. 윈도우에서는 `wsl.exe -d <배포판> -e …`, 맥·리눅스에서는 로컬 프로세스 — **OS 별 분기 없이 한 경로.**
- **화면이 죽어도 코어는 산다.** 세션이 유지되고, 다시 열면 붙는다.

---

## 2. 핵심 문제 — 세션을 소유하는 쪽과 그리는 쪽이 다른 OS 에 있다

선행 문서들의 같은 자리는 *"프로세스는 격리, 지식은 공유"*(workdir), *"서로를 기다리는 두 `claude`"*(session-tree) 였다. 이번 문서의 자리는 이렇다.

**지금 한 프로세스 안에서 함수 호출 하나로 끝나던 것이 OS 경계를 건넌다.** 무엇이 건너고, 건너면서 무엇이 깨지는지가 이 설계의 전부다.

| 건너는 것 | 한 JVM 일 때 | 둘로 가르면 |
|---|---|---|
| **수명** | 창을 닫으면 `endAllSessions` 를 기다린 뒤 끝난다(`Main.kt:198`). 앱과 세션이 생사를 같이한다(원칙 5 개정) | 화면이 죽어도 코어는 살아야 한다. 그러면 **누가 코어를 띄우고, 언제 끝내는가**가 새로 생긴다. 끝낼 쪽이 사라진 코어는 좀비다 |
| **재접속** | 없다. 창이 곧 프로세스다 | 화면이 다시 뜨면 살아 있는 코어를 찾아 붙어야 한다. stdio 는 한 번뿐이다 — 끊긴 파이프는 다시 이을 수 없다 |
| **상태** | `StateFlow` 를 그대로 구독한다 | 값을 직렬화해 보내야 한다. 챗 로그는 MB 단위라 바뀔 때마다 통째로 보낼 수 없다 |
| **물음 → 답** | 승인 카드의 답이 `CompletableDeferred` 에 바로 들어간다(`PermissionRequest.kt:57`). 관문 물음은 객체 정체성으로 찾는다(`ChatSessionStateHolder.kt:1400`) | 물음은 코어에 남고 답은 화면에서 온다. 화면이 답하기 전에 죽으면 물음은 다음 화면에게 가야 한다. 객체 정체성은 경계를 못 건넌다 |
| **경로** | 고른 폴더가 곧 세션의 cwd 다 | 윈도우 파일 선택기는 `\\wsl.localhost\…` 를 준다. 코어는 `/home/…` 를 쓴다 |
| **OS 기능** | 파일 선택기·클립보드가 세션과 같은 OS 에 있다 | 파일 선택기·클립보드·브라우저는 **사람이 앉은 OS**(윈도우)의 것이어야 하고, 프로세스·파일·비밀은 **세션의 OS**(WSL)의 것이어야 한다 |
| **비밀** | GitHub 토큰·claude 토큰이 같은 프로세스 안에서 `kyu` 로 간다 | 사람이 화면에 친 토큰이 파이프를 건너 코어로 간다 |
| **판(버전)** | 한 패키지라 언제나 같다 | 윈도우 msi 와 WSL 코어를 따로 깐다. 둘의 판이 갈릴 수 있다 |

---

## 3. 실측 — 설계의 갈림길 일곱

**2026-10-22, 윈도우 10.0.26300.9711, WSL 2.4.11.0(커널 5.15.167.4), Ubuntu-24.04(`systemd=true`).** `.wslconfig` 는 `vmIdleTimeout=-1` 이고 `networkingMode` 는 적혀 있지 않다(NAT). 재현 방법은 부록 B 다.

**윈도우 쪽 부모는 윈도우 JVM 이다.** 화면이 실제로 쓸 길과 같게 재려고, 윈도우에 깔린 OpenJDK 23(`C:\Users\maxim\.jdks\openjdk-23.0.2\bin\java.exe`)으로 하네스(`WinHarness`)를 돌렸다. 하네스는 `ProcessBuilder("C:\Windows\System32\wsl.exe", "-d", "Ubuntu-24.04", "-e", …)` 로 WSL 프로세스를 띄운다 — 화면 JVM 이 할 일 그대로다. WSL 쪽에는 코어 흉내를 내는 작은 프로그램들(파이썬·bash)을 두었다.

**판정은 WSL 쪽 사실로 한다.** 바이트는 되돌려 받은 base64 를 보낸 바이트와 견주어, 생사는 `pgrep` 과 프로세스가 매초 적은 기록과 받은 신호로, 윈도우 프로세스의 생사는 `tasklist.exe /FI "PID eq …"` 로 갈랐다.

### 3.1 stdio 로 UTF-8 JSON 줄 — `wsl.exe` 를 직접 부르면 바이트가 그대로 간다, PowerShell 을 거치면 깨진다

**(가) 윈도우 JVM → `wsl.exe -d Ubuntu-24.04 -e python3 echo_core.py`**

| 잰 것 | 결과 |
|---|---|
| 띄운 뒤 첫 줄(코어의 인사)까지 | **98 ms** (배포판이 이미 떠 있을 때) |
| 인사 줄의 한국어(`코어가 떴습니다 ✓`) | ✅ 그대로 |
| 보낸 줄 넷 — 한국어·이모지·결합 문자·JSON 이스케이프(`\u0000` `\t` `\n` `\r\n`)·윈도우 경로의 역슬래시 | ✅ **4/4 바이트가 같다**(받은 쪽 base64 를 되돌려 비교) |
| 윈도우 → WSL 방향 `\r` | ❌ 없다 (`endsWithCR=false`) |
| WSL → 윈도우 방향 `\r` | ❌ 없다 (stdout·stderr 둘 다) |
| stderr | stdout 과 따로 온다. 한국어 그대로 |
| 작은 줄 왕복 200 번 | p50 **199 µs** · p90 298 µs · p99 479 µs · 최대 615 µs |
| 큰 줄 1.26 MB 한 줄 왕복 | 65 ms (답은 base64 라 1.33 배) |
| 큰 줄 10.07 MB 한 줄 왕복 | 308 ms |
| 화면이 stdin 을 닫는다 | 코어가 EOF 를 읽고 정상 종료(`exit=0`) |

**줄 끝 변환도 인코딩 변환도 없다.** `wsl.exe` 는 바이트를 나른다. 코어는 줄마다 flush 했다 — 실제 코어도 틀(frame) 하나를 쓸 때마다 flush 해야 한다(6.4).

**(나) PowerShell 파이프라인을 거치면** — `'{"text":"안녕하세요 ✓"}' | wsl.exe -e python3 echo_core.py`

| PowerShell 설정 | 코어가 받은 바이트 |
|---|---|
| 기본(PowerShell 5.1.26100 — `$OutputEncoding=us-ascii`, 콘솔 `ks_c_5601-1987`) | `{"text":"????? ?"}\r` — **한국어가 `?` 로 사라지고 `\r` 이 붙는다** |
| `$OutputEncoding`·`[Console]::OutputEncoding` 을 UTF-8 로 바꾼 뒤 | 한국어는 맞다. **`\r` 은 그대로 붙는다** |

**화면은 셸을 거치지 않고 `wsl.exe` 를 직접 띄운다.** PowerShell·`cmd.exe` 는 그 사이에서 인코딩과 줄 끝을 고친다.

### 3.2 부모가 죽을 때 — `wsl.exe` 를 죽여도 WSL 프로세스는 죽지 않는다, `wsl.exe` 의 명령이 끝나면 같은 세션은 SIGHUP 을 받는다

WSL 쪽에 매초 "살아 있다" 를 적고 받은 신호(`HUP`·`TERM`·`PIPE`·`INT`)를 기록하는 `life.sh` 를 두고, 윈도우 JVM 하네스로 띄운 뒤 시나리오마다 다르게 끝냈다.

| 띄운 모양 | 끝낸 방법 | 4~7 초 뒤 WSL 프로세스 | 받은 신호 |
|---|---|---|---|
| A. 그대로(stdio 가 `wsl.exe` 에 물림) | **화면 JVM 이 갑자기 죽는다**(`Runtime.halt`) | ✅ 산다. **윈도우의 `wsl.exe` 도 남는다**(`tasklist` 에 그대로) | `SIGPIPE` 만(stdout 에 쓸 때). `SIGHUP` 없음 |
| B. 그대로 | **`wsl.exe` 를 강제로 죽인다**(`destroyForcibly`) | ✅ 산다 | `SIGPIPE` 만 |
| C. 그대로 | 화면이 stdin 만 닫는다 | ✅ 산다(이 프로그램은 stdin 을 안 읽는다) | 없음 |
| D. `nohup … &` 하고 명령은 곧바로 끝 | `wsl.exe` 가 스스로 끝난다 | ❌ **기록이 한 줄도 없다** — 뜨자마자 죽었다 | — |
| D2. 같은데 명령이 2 초 뒤에 끝 | 같다 | ✅ 산다 | 없음(`nohup` 이 HUP 을 무시) |
| I2. `… &` 하고 2 초 뒤 끝 | 같다 | ✅ 산다 — **`trap` 을 걸어 두었기 때문** | **`SIGHUP`, 명령이 끝난 바로 그 시각** |
| J2. `… & disown` 하고 2 초 뒤 끝 | 같다 | ✅ 산다(같은 이유) | **`SIGHUP`** |
| E. `setsid -f …` + stdio 를 `/dev/null` 로 | 같다 | ✅ 산다 | 없음 |
| F. `systemd-run --user …` | 같다 | ✅ 산다 | 없음 |
| G. `setsid -f …` 로 떼고 `wsl.exe` 는 중계처럼 남는다(`exec cat`) | `wsl.exe` 강제 종료 | ✅ 산다 | 없음 |
| H. G 와 같다 | 화면 JVM 이 갑자기 죽는다 | ✅ 산다. `wsl.exe` 도 곧 끝난다(`cat` 이 EOF) | 없음 |
| K3. `setsid …`(fork 없이) | `wsl.exe` 가 곧바로 끝난다 | ❌ 기록 0 줄 | — |

**읽어야 하는 것이 넷이다.**

1. **윈도우 쪽에서 `wsl.exe` 를 죽여도 리눅스 프로세스에는 신호가 가지 않는다**(A·B). 파이프만 끊긴다. 그래서 *"화면이 코어를 죽인다"* 는 `wsl.exe` 를 죽이는 것으로는 성립하지 않는다 — 코어에게 말로(stdin 을 닫거나 명령으로) 알려야 한다. 이것이 1 번 안을 기각한 실측 근거이기도 하다(4.1).
2. **반대로 `wsl.exe` 의 명령이 정상으로 끝나면, 그 명령의 세션에 남은 프로세스가 `SIGHUP` 을 받는다**(I2·J2 — 명령이 끝난 시각과 같은 밀리초). 받아 내지 못하면 죽는다(D·K3). 보낸 쪽이 커널인지 WSL 의 `init` 인지는 재지 않았다 — stdio 가 파이프라 제어 터미널이 없으므로 `init` 으로 추정한다.
3. **떼어 내는 것에도 경합이 있다.** 같은 모양을 10 번씩 돌렸다.

   | 모양 (`wsl.exe` 의 명령은 곧바로 끝난다) | 산 횟수 |
   |---|---|
   | `setsid -f`, stdio 를 `wsl.exe` 에 물린 채 | **7/10** |
   | `setsid -f`, stdio 를 `/dev/null` 로 | 10/10 |
   | `systemd-run --user` | 10/10 |
   | `setsid -f` + `/dev/null`, 명령이 0.5 초 기다린 뒤 끝 | 10/10 |

   첫 번째 줄의 실패는 실측 5 의 첫 시도에서 실제로 났다 — `setsid -f python3 socket_core.py` 가 기록 한 줄 없이 사라졌다. **떼어 낼 때는 stdio 를 끊고, 띄운 쪽은 떼어 낸 것이 준비됐다는 것을 확인한 뒤에 끝난다**(6.13).
4. **화면 JVM 이 정상으로 끝나도 자식 `wsl.exe` 는 죽지 않는다**(실측 5 의 세 번째 판 — JVM 이 `main` 을 빠져나가 끝났는데 중계가 남았다). 윈도우의 `ProcessBuilder` 자식은 부모와 함께 정리되지 않는다.

### 3.3 경로 — `wslpath` 가 두 형식을 다 풀고, 윈도우 JVM 은 `\\wsl.localhost` 를 디렉토리로 본다, 절대 심볼릭 링크는 못 따라간다

| 변환 | 결과 |
|---|---|
| `wslpath -w /home/yawn` | `\\wsl.localhost\Ubuntu-24.04\home\yawn` |
| `wslpath -w …/워크 디렉토리` (한국어·공백) | `\\wsl.localhost\Ubuntu-24.04\…\워크 디렉토리` |
| `wslpath -w /mnt/c/Users/maxim` | `C:\Users\maxim` |
| `wslpath -u '\\wsl.localhost\Ubuntu-24.04\…'` | `/…` ✅ |
| `wslpath -u '\\wsl$\Ubuntu-24.04\…'` | `/…` ✅ — 옛 형식도 푼다 |
| `wslpath -u 'C:\Users\maxim'` · `'c:/Users/maxim'` | `/mnt/c/Users/maxim` ✅ |
| `wslpath -u '\\wsl.localhost\ubuntu-24.04\home'` · `'\\WSL.LOCALHOST\…'` | `/home` ✅ — 배포판 이름·호스트 이름의 대소문자를 가리지 않는다 |
| `wslpath -u '\\wsl.localhost\docker-desktop\tmp'` | ❌ 오류 — **다른 배포판의 경로는 풀지 못한다** |

**윈도우 JVM(OpenJDK 23)이 `\\wsl.localhost\Ubuntu-24.04\…\워크 디렉토리` 를 보면**

| 잰 것 | 결과 |
|---|---|
| `File.isDirectory()` · `canRead()` | `true` · `true` |
| `getCanonicalPath()` · `toRealPath()` | 입력 그대로. **`\\wsl$` 로 준 것은 `\\wsl$` 그대로** — 두 형식을 하나로 맞춰 주지 않는다 |
| `Path.getRoot()` | `\\wsl.localhost\Ubuntu-24.04\` (UNC 루트) |
| `toUri()` | `file://wsl.localhost/Ubuntu-24.04/…/워크%20디렉토리/` |
| 목록 | `Repo` 와 `repo` **둘 다** 보인다(대소문자만 다른 두 폴더) |
| `FileSystemView.isTraversable` | `true` |
| 그 안의 **절대 경로 심볼릭 링크**(`kyu-link-probe → /tmp/…/target`) | 이름은 목록에 보이지만 `Files.exists(NOFOLLOW_LINKS)=false`, `toRealPath()` 는 `NoSuchFileException`. PowerShell `Get-Item .Target` 도 빈 값 |
| PowerShell `[System.IO.Path]::GetFullPath` · `Directory.Exists` · `Resolve-Path` | 두 형식 다 그대로 · `True` · 그대로 |
| 탐색기 왼쪽 "Linux" 노드의 경로 | `::{B2B4A4D1-2754-4140-A2EB-9A76D9D7CDC6}` — 파일 시스템 경로가 아니라 셸 이름 공간이다 |

**화면은 WSL 폴더를 "볼" 수는 있지만 리눅스와 같게 보지는 못한다.** 심볼릭 링크가 끊겨 보이고, 대소문자만 다른 이름이 윈도우 API 에서 어떻게 갈릴지는 쓰는 함수마다 다르다. **화면은 워크디렉토리의 파일 시스템을 만지지 않는다**(원칙 19). 고른 경로 문자열만 코어에게 넘기고, 코어가 리눅스 쪽에서 푼다(6.9).

### 3.4 배포판 목록 — 기본 출력은 UTF-16LE, `WSL_UTF8=1` 이면 UTF-8

| 명령 | 결과 |
|---|---|
| `wsl.exe -l -v` | **UTF-16LE**, BOM 없음(`2000 2000 4e00 4100 …`). 풀면 `* Ubuntu-24.04  Running  2` · `docker-desktop  Running  2` |
| 같은 명령에 환경 변수 `WSL_UTF8=1` | **UTF-8**, 줄 끝 `\r\n`. 같은 내용 |
| `wsl.exe -l -q` (`WSL_UTF8=1`) | 이름만 `Ubuntu-24.04\r\ndocker-desktop\r\n`. **기본 표시(`*`)가 없다** |
| `wsl.exe --version` (`WSL_UTF8=1`) | `WSL 버전: 2.4.11.0` … 한국어 로케일 문구 |
| 레지스트리 `HKCU\Software\Microsoft\Windows\CurrentVersion\Lxss` 의 `DefaultDistribution` → 그 키의 `DistributionName` | `Ubuntu-24.04` |

**`docker-desktop` 이 목록에 섞인다.** 사람이 쓰는 배포판이 아니다. 화면은 목록을 그대로 보여 주지 않고 거른 뒤, 각 배포판에 코어가 깔려 있는지 물어 고르게 한다(6.17). 기본 배포판은 `-l -v` 의 `*` 줄에서 읽는다 — 레지스트리는 같은 답을 주지만 읽는 길이 하나 더 는다.

### 3.5 재접속 통로 — Unix 소켓 + `wsl.exe -e <중계>` 가 된다, 중계가 stdin EOF 를 넘기지 않으면 좀비가 남는다

stdio 는 한 번뿐이다. 화면이 끊긴 뒤 다음 화면이 코어에 붙으려면 코어가 **WSL 안의 Unix 소켓**에서 기다리고, 윈도우 화면은 `wsl.exe -e <중계> <소켓>` 의 stdio 로 그 소켓에 닿아야 한다. 중계로 OpenBSD `nc -U`(Ubuntu 기본 설치)를 썼다. 코어 흉내(`socket_core.py`)는 연결이 오면 **지금까지 받은 명령 수(= 코어가 쥔 상태)** 를 스냅샷으로 먼저 보내고 명령마다 답한다. 코어는 3.2 의 E 모양(`setsid -f` + `/dev/null` + 소켓이 설 때까지 기다림)으로 `wsl.exe` 를 거쳐 띄웠다. `XDG_RUNTIME_DIR` 은 `wsl.exe -e` 로 뜬 프로세스에서도 `/run/user/1000/` 이었다(`systemd=true`).

| 화면 | 중계 | 끊은 방법 | 결과 |
|---|---|---|---|
| S1 | `nc -U` | `wsl.exe` 강제 종료 | ✅ 중계가 사라지고 코어가 같은 초에 EOF. **다음 화면의 스냅샷이 `commandsSeen: 102`** — 상태가 이어졌다 |
| S2 | `nc -U` | 화면 JVM 이 갑자기 죽는다 | ❌ **중계(`nc`)와 윈도우의 `wsl.exe` 가 남는다.** 첫 판에서 5 분 8 초 뒤에도 그대로였다. 코어는 그 화면이 아직 붙어 있다고 안다 |
| S3 | `nc -U` | 화면이 stdin 을 닫는다(정상으로 손을 놓음) | ❌ 3 초 동안 아무것도 오지 않고 중계도 안 끝난다 — **OpenBSD `nc` 는 `-N` 없이 stdin EOF 를 소켓으로 넘기지 않는다** |
| N1 | `nc -N -U` | 화면 JVM 이 갑자기 죽는다 | ✅ 코어가 1 초 안에 EOF. 중계 사라짐 |
| N2 | `nc -N -U` | 화면이 stdin 을 닫는다 | ✅ 코어 EOF, 중계가 스스로 끝난다(`relayExited=true`) |
| N3 | `nc -N -U` | 화면 JVM 이 정상으로 끝난다(자식을 정리하지 않고) | ✅ 코어가 같은 초에 EOF |
| — | — | 코어를 끝낸다(`shutdown` 명령) | 남아 있던 `nc -U` 중계들도 함께 끝났다 |

| 잰 것 | 결과 |
|---|---|
| 중계를 띄운 뒤 스냅샷 줄까지 | 62 ~ 82 ms |
| 중계 너머 작은 줄 왕복 100 번 | p50 231 ~ 297 µs · p90 330 ~ 460 µs · 최대 ≈ 810 µs (직결 3.1 보다 수십 µs 느림) |
| 한국어 · `\r` | 그대로 · 없음 |

**읽어야 하는 것이 둘이다.**

1. **통로는 된다.** Unix 소켓에서 기다리는 코어에 윈도우 화면이 `wsl.exe` 의 stdio 로 붙고, 끊기고, 다시 붙는다. 상태는 연결과 무관하게 코어에 남는다.
2. **중계는 stdin EOF 를 반드시 코어에게 넘기고 스스로 끝나야 한다.** 그러지 않으면 화면이 죽은 뒤에도 중계와 `wsl.exe` 가 남고(S2), 코어는 그 연결을 산 화면으로 안다. 첫 판의 코어는 연결을 하나씩만 받아서 그 좀비가 **다음 화면을 통째로 막았다**(부록 B 의 `m5-first-zombie.log`). 그래서 6.13 은 중계를 우리 코드로 두고(4.9), 코어는 새 화면이 붙으면 옛 연결을 놓는다.

### 3.6 JVM 둘 — 헤드리스 코어의 바닥은 60 MB 안쪽, 클래스패스의 86 % 는 화면 것

**클래스패스**(`desktop` 의 `runtimeClasspath`, jar 55 개)를 화면만 쓰는 묶음과 나머지로 갈랐다.

| 묶음 | 크기 | 큰 것 |
|---|---|---|
| 화면 전용(Compose·Skiko·androidx·마크다운 렌더러·하이라이터) | **33.7 MB** | `skiko-awt-runtime-linux-x64` 12.1 MB, `foundation-desktop` 4.6 MB, `material3-desktop` 4.3 MB, `ui-desktop` 3.6 MB |
| 나머지 | 5.5 MB | `kotlin-stdlib` 1.8 MB, `kotlinx-coroutines-core-jvm` 1.5 MB, `kotlinx-serialization-*` 0.7 MB — 여기에 렌더러가 끌고 온 `markdown-jvm`·`kotlinx-datetime`·`collections-immutable` 이 섞여 있어, 코어만의 것은 **4 MB 남짓**이다 |

**헤드리스 코어의 바닥값** — `kotlin-stdlib`·`kotlinx-coroutines`·`kotlinx-serialization-json` 만 실은 JVM(WSL 의 OpenJDK 21.0.12)이 stdin 의 JSON 줄 200 개를 파싱해 답하게 했다.

| 플래그 | 첫 줄까지 | 3 초 뒤 RSS |
|---|---|---|
| 기본 | 68 ~ 85 ms | 57.5 ~ 59.5 MB |
| `-Xmx128m -XX:+UseSerialGC` | 81 ms | 56.4 MB |
| `-XX:TieredStopAtLevel=1 -Xmx128m -XX:+UseSerialGC` | 64 ms | 53.7 MB |

**가늠이다.** 실제 코어는 세션마다 `claude` 이벤트를 접고 전사를 쥐므로 이보다 크다. 다만 Compose·Skiko 를 싣지 않는 것만으로 코어 하나는 수십 MB 대에 선다는 것, 그리고 JVM 을 하나 더 띄우는 시작 비용이 100 ms 안쪽이라는 것은 말할 수 있다. 화면 JVM 의 RSS 는 재지 않았다(3.8).

### 3.7 덤으로 온 것 — PATH, 인자의 따옴표, `--cd ~`

| 잰 것 | 결과 | 설계 |
|---|---|---|
| `wsl.exe -e python3 …` 로 뜬 프로세스의 `PATH` | `~/.local/bin` 이 **없다**. `claude`·`kyu`(둘 다 `~/.local/bin`) 를 못 찾는다. 윈도우의 `PATH` 가 `/mnt/c/…` 로 뒤에 붙어 온다 | 코어는 지금 앱처럼 로그인 셸에게 PATH 를 묻고 잘 알려진 자리를 더한다(`platform/LoginShellSearchPath.kt` 를 그대로 가져간다) |
| `wsl.exe -e bash -lc …` | 찾는다(`~/.profile` 이 넣는다) | — |
| `wsl.exe -e zsh -lc …` | **못 찾는다** — 이 사용자의 zsh 는 `~/.zshrc`(대화형에서만 읽힘)에서 넣는다 | 로그인 셸을 거치는 것만으로는 부족하다는 근거. 지금 앱의 "잘 알려진 자리" 보강이 그대로 필요하다 |
| `wsl.exe -e zsh -ic …` | 찾는다 | 대화형 셸로 띄우지는 않는다(`LoginShellSearchPath.kt` 가 적은 이유 그대로) |
| 윈도우 JVM 이 `wsl.exe -e python3 printargs.py <인자>` 로 넘긴 인자 | `한국어 공백` · `back\slash` · `$HOME` · `a;b` · `tail\` 는 그대로. **`"quoted"` 는 `quoted` 로 따옴표가 사라진다** | 윈도우의 명령줄은 문자열 하나다. **`wsl.exe` 에는 단순한 인자만 넘기고, 나머지는 전부 stdio 로 보낸다**(6.12) |
| `wsl.exe -d Ubuntu-24.04 --cd ~ -e pwd` | `/home/yawn` | 화면은 사용자의 리눅스 홈 경로를 몰라도 된다 |
| `wsl.exe -d Ubuntu-24.04 --cd ~ -e .local/bin/kyu version` | `kyu v0.13.0 (3edd9cf05752)` | **셸도 따옴표도 없이** `~/.local/bin` 의 실행 파일을 띄우는 길(6.12) |

### 3.8 재지 않은 것

| 무엇 | 왜 못 쟀나 | 어디서 다시 재나 |
|---|---|---|
| **모든 `wsl.exe` 가 끝난 뒤 배포판이 스스로 멈추는가** — 화면이 닫히고 떼어 낸 코어만 남았을 때 | 이 머신에는 지금 이 세션을 포함해 늘 다른 `wsl.exe` 가 떠 있고, `.wslconfig` 가 `vmIdleTimeout=-1` 이다. 재려면 내 세션을 끊어야 한다. WSL 이 마지막 클라이언트가 떠난 뒤 배포판을 멈추는 동작이 있다는 것은 알려져 있지만(문서·이슈에서 본 것, 실측 아님) 그 조건과 시간은 판마다 다르다 | **7 절 5 단계의 완료 확인에 넣는다.** 결과에 따라 6.13 의 "코어가 화면 없이 사는 시간" 이 배포판의 수명에 묶인다 |
| 배포판이 멈춰 있을 때 첫 `wsl.exe` 의 시작 시간 | 위와 같다 | 5 단계 |
| 윈도우 네이티브 폴더 선택 창(`IFileOpenDialog`)에서 "Linux" 아래 폴더를 고르면 주는 경로 | 사람이 눌러야 한다. 셸 노드가 GUID 라는 것(3.3)까지만 쟀다 | **3 단계의 완료 확인.** `\\wsl.localhost\…` 로 올 것으로 본다(추측) |
| 화면 JVM(Compose)의 RSS | 이 머신에서 창을 띄우면 사용자의 데스크톱에 뜬다. Xvfb 가 없다 | 2 단계에서 코어를 뗀 뒤 화면 하나만 띄워 잰다 |
| 맥에서 떼어 낸 코어가 화면과 무관하게 사는가 | 맥이 없다. 3.2 의 `SIGHUP` 은 WSL `init` 의 동작으로 추정되어 맥에는 없을 수 있다 | 5 단계, 맥 |
| `jpackage --type msi` 가 CI 에서 실제로 나오는가 | 윈도우 러너가 필요하다. Compose 플러그인 1.12.0 jar 안에 `downloadWix`·`unzipWix` 태스크와 `wix311`, `WIX_PATH`, `compose.desktop.application.downloadWix` 문자열이 있는 것까지만 봤다 — 플러그인이 WiX 3.11 을 스스로 받는 것으로 읽힌다(실측 아님) | 4 단계 |
| WSL 의 비밀 저장소 | `secret-tool` 이 없어 엔진은 설정 파일 폴백을 쓴다(`internal/secretstore/vault.go:39`). 코어가 WSL 에 있어도 지금 WSL deb 사용자와 같은 처지라 새로 잴 것이 없다 | — |
| 코어(WSL)의 `claude auth login` 이 어느 브라우저를 여는가 | 실제 로그인을 해야 한다 | 3 단계. 화면이 주소를 받아 윈도우 브라우저로 여는 길을 따로 둔다(6.10) |
| `networkingMode=mirrored` 에서의 동작 | 이 머신은 NAT 다. 이 설계는 TCP 를 쓰지 않으므로 갈리지 않을 것으로 본다(추측) | — |
| `/mnt/c` 아래 워크디렉토리의 속도 | 이 설계의 질문이 아니다. 9P 를 거쳐 느리다는 것은 널리 알려져 있다(실측 아님) | 열린 질문 6 |

### 3.9 실측이 설계에 미친 영향 — 한눈에

| 실측 | 결과 | 설계 |
|---|---|---|
| 3.1 stdio | 바이트가 그대로, `\r` 없음, 왕복 0.2 ms, 10 MB 줄 0.3 s. PowerShell 경유는 깨진다 | 화면은 `wsl.exe` 를 직접 띄운다. 틀 하나를 1 MiB 안쪽으로 나눈다 — 6.4 · 6.6 · 6.12 |
| 3.2 수명 | `wsl.exe` 를 죽여도 리눅스 쪽은 신호 없음. 명령이 끝나면 같은 세션에 `SIGHUP`. `setsid -f` 에 경합 | 화면이 코어를 끝낼 때는 말로 한다. 데몬은 세션을 떼고 stdio 를 끊고, 띄운 쪽이 준비를 확인한 뒤 끝난다 — 6.13 |
| 3.3 경로 | `wslpath` 가 두 형식을 푼다. 다른 배포판은 못 푼다. 윈도우는 리눅스 심볼릭 링크를 못 따라간다 | 화면은 문자열만 넘기고 코어가 푼다. 다른 배포판이면 거절 — 6.9 |
| 3.4 배포판 | UTF-16LE, `WSL_UTF8=1` 로 UTF-8. `docker-desktop` 이 섞인다 | 첫 실행의 배포판 고르기 — 6.17 |
| 3.5 재접속 | Unix 소켓 + 중계가 된다. 중계가 EOF 를 안 넘기면 좀비 | 코어 데몬 + `attach` 중계를 우리 코드로. 새 화면이 옛 연결을 밀어낸다 — 6.13 · 4.9 |
| 3.6 JVM | 코어 바닥 ≈ 55~60 MB, 시작 < 100 ms. 클래스패스의 86 % 가 화면 것 | `core` 모듈은 Compose 를 의존하지 않는다 — 6.15 |
| 3.7 덤 | `wsl.exe -e` 의 PATH 에 `~/.local/bin` 없음. 인자의 `"` 가 사라진다. `--cd ~` 가 된다 | 화면은 `wsl.exe -d <배포판> --cd ~ -e .local/bin/kyuchestration-core <동사>` 만 넘긴다 — 6.12 |

---

## 4. 검토한 대안과 기각 사유

### 4.1 안 1 — 앱이 `wsl.exe` 로 프로세스를 감싸는 WSL 백엔드 모드

윈도우에 앱 하나를 두고, 앱이 띄우는 모든 프로세스(`claude`·`kyu`)를 `wsl.exe -e` 로 감싼다. 세션의 소유자는 지금처럼 앱이다.

| 기각 사유 | 근거 |
|---|---|
| **앱 소켓이 OS 경계를 못 건넌다** | `kyu mcp serve`(WSL)가 앱(윈도우)의 앱 소켓에 붙어야 한다(chat-ui 5.4, session-tree 6.4). 윈도우 AF_UNIX 소켓은 WSL2 VM 안에서 열 수 없다. **WSL → 윈도우 방향 TCP 는 NAT 모드에서 안 통한다** — 윈도우 호스트의 주소를 알아내야 하고 방화벽이 막는다(미러 모드에서는 다를 수 있지만 사용자 설정에 기대게 된다). **안전한 것은 stdio 중계뿐이다** — 그러면 세션마다 `kyu mcp serve` 의 소켓 요청을 `wsl.exe` 의 stdio 로 감싸 앱까지 나르는 채널을 새로 짜야 한다. 승인·`spawn`·`await`·`ask_parent` 가 전부 그 위로 간다 |
| **`wsl.exe` 를 죽여도 `claude` 가 안 죽는다** | 3.2 의 B. 앱이 세션을 끝낼 때 프로세스 핸들을 죽이는 길(`ProcessChatSession` 의 마지막 수단)이 WSL 쪽에서는 아무것도 하지 않는다. 앱이 크래시하면 `claude` 가 남는다 — app-owned 열린 질문 1 의 고아가 확실히 생긴다 |
| **프로세스 하나하나가 OS 경계를 건넌다** | `claude` 뿐 아니라 `kyu list`·`kyu session-command`·`git` 이 모두 `wsl.exe` 를 거친다. 3 초마다 도는 관찰(`WorkDirDashboardStateHolder.DEFAULT_REFRESH_INTERVAL`)이 매번 `wsl.exe` 를 띄운다 |
| **경로가 모든 자리에 섞인다** | 세션의 cwd·`--mcp-config` 의 소켓 경로·엔진이 돌려준 경로가 전부 리눅스 경로인데 앱은 윈도우에 있다. 변환이 한 자리가 아니라 모든 호출에 붙는다 |
| **로컬 앱(맥·리눅스)과 다른 길** | 윈도우에만 있는 백엔드가 생긴다. 원칙 20 의 반대편이다 |

### 4.2 안 2 — WSLg 를 유지한다

| 기각 사유 | 근거 |
|---|---|
| **사용자 요구가 정면으로 막는다** | *"창은 윈도우 네이티브"*. WSLg 창은 RDP 너머의 리눅스 창이다 — IME·클립보드·배율·작업 표시줄이 윈도우의 것이 아니다 |
| 윈도우 쪽 도구와 이어지지 않는다 | 윈도우 브라우저·탐색기·윈도우 클립보드와 오가는 길이 WSLg 를 한 번 더 거친다 |

### 4.3 코어를 Go 데몬으로 새로 짠다

| 기각 사유 | 근거 |
|---|---|
| **사용자가 정했다** | *"Go 데몬을 새로 짜지 않는다"* |
| 같은 일을 두 언어로 | `ChatSessionStateHolder`(1463 줄)·`SessionTree`·`AppSocket` 과 그 시험(실 `claude` 통합 시험 포함)이 이미 코틀린에 있다. 옮겨 짜면 두 구현이 조용히 갈라진다(원칙 11 이 엔진·앱 사이에서 경계한 그 일) |

### 4.4 데몬 없이 — 코어를 화면의 stdio 에 직접 물리고 끝

화면이 코어를 띄우고, 코어는 stdin 이 닫히면 세션을 다 끝내고 죽는다.

**단계 2~4 는 이것이다**(7 절). 지금의 원칙 5(개정)와 같은 수명이고, 3.1·3.5 의 N1 이 보인 대로 화면이 크래시해도 코어가 stdin EOF 를 받으므로 고아가 남지 않는다. **그러나 끝 모양으로는 기각한다.**

| 기각 사유 | 근거 |
|---|---|
| 화면이 죽으면 세션이 죽는다 | 사용자 요구 *"화면이 죽어도 코어는 살아야 한다"* |
| EOF 를 무시하고 살아남게 하면 다시 붙을 길이 없다 | 3.2 의 A·B — 프로세스는 살지만 stdio 가 끊긴다. 그 코어에게 다시 말을 걸 통로가 없다. 사는 것과 닿는 것은 다르다 |

### 4.5 화면 ↔ 코어를 TCP(localhost)로

윈도우 → WSL 방향은 기본 `localhostForwarding` 으로 닿는다(4.1 의 반대 방향).

| 기각 사유 | 근거 |
|---|---|
| 누가 붙는지 모른다 | 그 머신의 아무 프로세스나 그 포트에 붙어 승인 카드에 답하고 세션에 말을 걸 수 있다. 토큰을 두면 그 토큰을 어디에 두고 어떻게 건네는지가 새로 생긴다. stdio 는 띄운 쪽만 닿는다 — 인증이 구조에서 나온다 |
| 포트가 생긴다 | 포트 고르기·충돌·방화벽 물음 |
| 맥·리눅스와 길이 갈린다 | 같은 머신이면 TCP 를 쓸 이유가 없다. 한 길이 아니게 된다(원칙 20) |

### 4.6 상태 스냅샷을 통째로, 바뀔 때마다

| 기각 사유 | 근거 |
|---|---|
| 챗 로그가 크다 | 도구 결과가 쌓이면 대화 하나가 MB 를 넘는다. 3.1 에서 10 MB 한 줄이 308 ms. 글자가 흐를 때(`streamingText`)마다 그것을 보낼 수는 없다 |
| 작은 상태는 통째로 보낸다 | **반만 기각한다.** 엔진 설치·인증·대시보드·트리처럼 작은 상태는 바뀔 때 통째로 보내는 것이 가장 단순하다(6.5) |

### 4.7 `ChatSessionEvent` 를 그대로 흘리고 화면이 접는다

`claude` 의 줄을 읽어 만든 사건(`ChatSessionEvent`)을 화면에 보내고, 화면이 `ChatConversation.after(event)`(`ChatConversationFolding.kt:18`)로 접는다.

| 기각 사유 | 근거 |
|---|---|
| **대화를 바꾸는 길이 사건 하나가 아니다** | 홀더는 사건 접기 말고도 대화를 직접 고친다 — 보낸 말을 대기열에 얹고(`ChatSessionStateHolder.kt:309`), 중단 중으로 바꾸고(`:331`), 승인 카드에 답을 적는다(`:1176`). 사건만 보내면 화면이 그 고침을 다시 해야 하고, 그러면 **접는 자리가 둘**이 된다 |
| 원칙 14 와 어긋나지 않게 하려면 오히려 반대 | 전사의 출처는 `claude` 의 스트림이고 그것을 접는 자리는 코어 하나여야 한다. 화면은 접힌 결과를 받는다 |

### 4.8 범용 JSON Patch(RFC 6902)

| 기각 사유 | 근거 |
|---|---|
| 뜻이 사라진다 | `/conversations/2/entries/417/answer` 같은 경로는 무엇이 바뀌었는지 말하지 않는다. 화면이 "새 줄이 붙었다" 와 "카드에 답이 왔다" 를 갈라 그리려면 경로를 다시 해석해야 한다 |
| 목록 위치가 흔들린다 | 지운 뒤 붙이면 인덱스가 밀린다. 우리 대화는 거의 꼬리에만 붙고 바뀐다 — 그 성질에 맞춘 좁은 변화(6.5)가 더 짧고 읽힌다 |

### 4.9 중계 — 무엇이 stdio 와 Unix 소켓을 잇는가

| 안 | 판단 |
|---|---|
| (가) **코어 자신의 `attach` 동사** — `kyuchestration-core attach` 가 데몬을 찾고(없으면 띄우고) 소켓에 붙어 stdio 를 잇는다 | **추천.** 중계와 데몬이 같은 실행 파일이라 판(버전)이 같다 — 붙는 순간 데몬의 판을 알아보고 낡은 데몬을 갈아 끼우는 일(6.14)을 중계가 할 수 있다. stdin EOF 를 넘기고 끝나는 동작(3.5)을 우리가 시험으로 고정한다. 대가는 JVM 하나가 화면이 붙어 있는 동안 더 떠 있는 것(≈ 55 MB, 3.6) |
| (나) `kyu relay <소켓>` — Go 엔진에 중계를 둔다 | 가볍다(Go 바이너리, 즉시 시작). 그러나 화면 ↔ 코어의 통로가 엔진의 일이 된다 — 엔진은 세션 조립의 지식을 쥐는 자리지(원칙 11) 앱의 배관이 아니다. 판 확인도 엔진·코어 둘에 걸친다. 코어의 메모리가 문제가 되면 다시 본다 |
| (다) `nc -N -U` · `socat` | 3.5 가 보인 대로 `-N` 하나를 빠뜨리면 좀비다. 배포판마다 있는지·어느 `nc` 인지가 다르다(이 머신에 `socat` 은 없다) |

---

## 5. 설계 원칙 — 상속, 개정 하나, 추가 넷

workdir 4 절의 원칙 1~6(5 는 app-owned 가 개정), app-owned 의 11·12, orchestration 의 13, chat-ui 의 14·15, session-tree 의 16·17·18 을 상속한다. 이 설계가 특히 계속 부딪히는 셋을 먼저 적어둔다.

> 4. **플랫폼 의존을 한 겹에 가둔다** — OS 가 갈리는 자리는 "코어를 어떻게 띄우는가" 한 함수(6.12)와 화면의 OS 기능(6.10)뿐이다.
> 11. **조립 지식은 엔진에 하나만 둔다** — 코어로 옮겨도 그대로다. 코어는 `kyu session-command` 의 답을 실행할 뿐이다.
> 16. **앱이 세션 트리의 유일한 소유자다** — 이 문서부터 **"앱" 은 코어를 가리킨다.** 화면은 트리를 그릴 뿐 소유하지 않는다.

**개정**

> 5. **(재개정) 도구가 죽어도 대화가 안 끊긴다** — CLI 세션의 생명주기는 도구와 독립적이다. GUI 세션은 **코어**와 생사를 같이한다. 화면이 닫히거나 죽어도 코어와 세션은 남고, 다음 화면이 붙어 그대로 이어 본다. 코어가 끝날 때는 지금처럼 모든 세션이 함께 끝나고, 다시 뜬 코어가 `claude --resume` 으로 대화를 이어간다.

app-owned 의 개정(*"GUI 세션은 앱과 생사를 같이한다"*)은 창과 세션 소유자가 한 프로세스라서 나온 문장이었다. 그 둘이 갈라지므로 문장도 가른다. 잃던 것(진행 중인 턴)을 이제 화면을 닫는 것으로는 잃지 않는다.

**추가**

19. **OS 기능은 화면이, 세션은 코어가** — 사람이 앉은 OS 의 것(창·파일 선택기·클립보드·브라우저 열기·알림)은 화면이 하고, 세션의 OS 의 것(프로세스·워크디렉토리의 파일·비밀·`claude`·`kyu`)은 코어가 한다. **화면은 워크디렉토리의 파일 시스템을 만지지 않는다** — 3.3 이 보인 대로 윈도우는 같은 폴더를 다르게 본다(끊긴 심볼릭 링크). 화면이 파일을 읽어 판단하기 시작하면 그 판단은 코어가 보는 사실과 조용히 갈라진다.

20. **한 경로 — 같은 머신도 원격처럼** — 화면은 언제나 코어를 자식 프로세스로 띄우고 stdio 의 JSON 줄로만 말한다. 맥·리눅스에서도 코어를 같은 JVM 에 들이는 지름길을 두지 않는다. 지름길이 있으면 개발자가 매일 쓰는 길(맥·리눅스)과 사용자가 쓰는 길(윈도우)이 갈리고, 경계에서 나는 결함은 사용자의 머신에서만 드러난다.

21. **화면은 언제든 죽어도 된다** — 코어는 화면의 작별 인사에 기대지 않는다. 화면에만 있는 상태는 보는 방식(테마·지금 보는 세션·스크롤·입력 중인 글)뿐이고, 그 밖의 모든 것은 다음 화면이 스냅샷 한 번으로 되찾는다. 그래서 **물음(승인·관문)은 코어에 남는다** — 답하기 전에 화면이 죽으면 그 물음은 다음 화면에 그대로 선다.

22. **경계를 건너는 것은 값뿐이다** — 프로세스 핸들·`Deferred`·람다·객체 정체성은 건너지 않는다. 물음은 코어가 지은 id 로, 답은 그 id 를 단 명령으로 건넌다. 화면이 코어의 객체를 쥐는 길이 없어야, 화면이 죽었을 때 코어 안에 주인 없는 객체가 남지 않는다.

---

## 6. 아키텍처

### 6.1 배치

```
윈도우                                              WSL2 (Ubuntu-24.04)
┌──────────────────────────────┐                   ┌──────────────────────────────────────────────────────┐
│ [화면] Kyuchestration.exe     │                   │ kyuchestration-core attach   ← 화면마다 하나, 중계    │
│  CoreLauncher                 │  wsl.exe -d D     │   stdin/stdout ◀──▶ core.sock                        │
│   └ wsl.exe ─────────────────────── --cd ~ -e ──▶ │                       │                              │
│  CoreMirror (StateFlow 거울)  │   stdio JSON 줄   │                       ▼                              │
│  Compose 화면                 │                   │ kyuchestration-core serve    ← 데몬, 사용자당 하나    │
│  파일 선택기 · 클립보드 · 브라우저│                   │   ScreenPublisher (StateFlow → 변화)                │
└──────────────────────────────┘                   │   상태 홀더들 · SessionTree · AppSocket(세션마다)      │
                                                   │     │ stdin/out                                      │
                                                   │   claude ── kyu mcp serve ── 앱 소켓                  │
                                                   └──────────────────────────────────────────────────────┘

맥 · 리눅스: 같은 그림에서 wsl.exe 칸만 빠진다. 화면이 <앱>/core/bin/kyuchestration-core attach 를 로컬 자식으로 띄운다.
(2 단계 정정) 맥 · 리눅스 패키지에는 실행기가 하나다 — 화면은 자기 실행기를 `<앱>/bin/Kyuchestration core serve --stdio` 로 다시 띄운다(6.16).
```

**`serve` 와 `attach` 는 같은 실행 파일의 두 동사다.** `serve` 는 세션을 쥐는 데몬, `attach` 는 화면의 stdio 를 데몬의 소켓에 잇는 중계다(4.9). 단계 2~4 에는 데몬이 없다 — 화면이 `serve --stdio` 를 직접 띄우고 코어는 stdin EOF 에 끝난다(4.4). 데몬과 `attach` 는 5 단계에 선다.

### 6.2 경계 — 화면이 지금 상태 홀더에 닿는 자리 전부

**이 표가 곧 프로토콜이다.** 화면 파일들(`KyuchestrationDesktopScreen.kt`·`Main.kt`·`WorkDirSessionPanel.kt`·`ChatConversationPane.kt`·`ChildSessionGateDialog.kt`·`ClaudeAuthenticationScreen.kt`·`EngineInstallationScreen.kt`·`RepositoryCloneDialog.kt`·`WorkDirDirectoryChooser.kt`)이 읽는 것과 부르는 것을 셌다. 화면 안쪽의 컴포저블들은 위에서 받은 값만 쓰고 홀더에 직접 닿지 않는다 — 닿는 자리는 `Main.kt` 의 조립과 `RepositoryCloneDialog.kt` 둘뿐이다(`KyuchestrationDesktopScreen.kt:89` 는 클론 홀더를 대화상자에 건네기만 한다).

**(가) 화면이 읽는 상태 — 아홉 갈래**

| 읽는 자리 | 홀더의 값 | 타입 | 크기 | 경계에서 |
|---|---|---|---|---|
| `Main.kt:161` | `EngineInstallationStateHolder.state` | `EngineInstallationState` | 작다 | 통째로. `InstallationFailed.failure` 는 `Throwable` 이라 값으로 바꾼다(6.3) |
| `Main.kt:162` | `ClaudeAuthenticationStateHolder.state` | `ClaudeAuthenticationState` | 작다 | 통째로. `failure` 같은 처리 |
| `Main.kt:163` | `WorkDirDashboardStateHolder.state` | `WorkDirDashboardState` | 레포 수만큼 | 통째로. `workDirPath: Path` 는 코어 경로 문자열 |
| `Main.kt:164` | `WorkDirInitializationStateHolder.state` | `WorkDirInitializationState` | 작다 | 통째로 |
| `Main.kt:165` | `ChatSessionStateHolder.state` | `ChatScreenState` | **크다** — `ChatOnScreen.conversation` 이 대화 전체 | **갈라진다**(6.8). "어느 세션을 보는가" 는 화면으로, 대화는 세션별 변화로 |
| `Main.kt:168` | `ChatSessionStateHolder.heldSessions` | `List<HeldChatSession>` | — | **대상(`target`)만**. 화면은 이미 대상만 쓴다(`Main.kt:220`). `HeldChatSession` 은 프로세스·소켓을 쥐어 못 건넌다 |
| `Main.kt:169` | `ChatSessionStateHolder.sessionTree` | `SessionTree` | 하위 수만큼 | 통째로. `workingDirectory: Path`·`startedAt: Instant` 는 문자열 |
| `Main.kt:170` | `ChatSessionStateHolder.gateQuestions` | `List<ChildSessionGateQuestion>` | 작다 | 통째로, **코어가 지은 id 를 달아서**(6.7) |
| `RepositoryCloneDialog.kt:76` | `RepositoryCloneStateHolder.state` | `RepositoryCloneState` | 저장소 목록만큼 | 통째로 |

화면이 읽지 않는 것: `ClaudeAuthenticationStateHolder.tokenForSessions`(`Main.kt:156`) — 챗 홀더가 세션을 띄울 때 묻는 코어 안의 값이다. 코어에 남는다.

**(나) 화면이 부르는 함수 — 명령이 된다**

| 부르는 자리 | 지금 | 명령 | 비고 |
|---|---|---|---|
| `Main.kt:224` · `:225` | `installEngine()` · `lookForEngineAgain()` | `install_engine` · `look_for_engine_again` | |
| `Main.kt:180` | `LaunchedEffect` 가 `EngineReady` 를 보고 `checkCredentials()` | — | **코어 안으로 옮긴다.** 홀더 사이의 순서는 조립하는 쪽의 결정이고(`Main.kt:55`), 조립은 이제 코어에 있다 |
| `Main.kt:226~230` | `checkCredentials` · `startBrowserLogin` · `submitBrowserLoginCode(code)` · `cancelBrowserLogin` · `useToken(token)` | 같은 이름 다섯 | `use_claude_token` 은 **비밀을 싣는다**(6.11) |
| `Main.kt:231~241` | 파일 선택기 → `openChosenDirectoryAsWorkDir(path, onWorkDirReady = dashboard::openWorkDir)` | `open_workdir { hostPath }` | 홀더 둘을 람다로 잇던 것이 코어 안의 한 명령이 된다. 경로는 코어가 푼다(6.9) |
| `Main.kt:243` | `initializeOpenedWorkDir(path)` | `initialize_opened_workdir` | 경로를 싣지 않는다 — 열린 워크디렉토리는 코어가 안다 |
| `Main.kt:245~253` | `repositoryCloneStateHolder.open(path, 이미 받은 레포 이름들)` | `open_repository_clone` | 같은 이유로 인자 없음 |
| `Main.kt:255` | `refreshNow()` | `refresh_workdir` | |
| `Main.kt:256~266` | `endAllSessions` + `forgetLastFailure` + `clone.close` + `closeWorkDir` | `close_workdir` | 네 걸음의 순서가 코어 한 자리로 |
| `Main.kt:267~271` | `enterSession(workDirPath, target, choice)` | `enter_session { target, conversationChoice }` | |
| `Main.kt:272` | `sendUserMessage(text)` — **화면에 보이는 세션**으로 | `send_user_message { target, text }` | **대상을 명시한다**(6.8) |
| `Main.kt:273` | `interruptOnScreenTurn()` | `interrupt_turn { target }` | 같다 |
| `Main.kt:274` | `answerOnScreenPermission(toolUseId, choice)` | `answer_permission { target, toolUseId, choice }` | 같다. 6.7 |
| `Main.kt:276` | `endOnScreenSession()` | `end_session { target }` | 같다 |
| `Main.kt:280` | `showChildSession(childId)` | — | **화면 안의 일이 된다**(6.8) |
| `Main.kt:281` | `answerGateQuestion(question, approved)` | `answer_gate { gateQuestionId, approved }` | 객체 대신 id(원칙 22) |
| `Main.kt:198` | 창을 닫을 때 `runBlocking { endAllSessions() }` | 단계 2~4: stdin 을 닫는다(코어가 다 끝낸다). 5 단계: **떼기만 한다**, `shutdown_core` 는 사람이 고를 때만 | 6.13 |
| `RepositoryCloneDialog.kt:87~171` | `close` · `chooseTokenProfile` · `startRemovingTokenProfile` · `stopRemovingTokenProfile` · `removeChosenTokenProfile` · `startRegisteringTokenProfile` · `stopRegisteringTokenProfile` · `registerTokenProfile(name, token)` · `continueWithRegisteredTokenProfile` · `chooseOwner` · `changeRepositorySearchText` · `toggleRepositorySelection` · `cloneSelectedRepositories` · `retryFailedStep` | 같은 이름 열넷 | `register_token_profile` 은 비밀을 싣는다. 검색어는 글자마다 건너지만 왕복이 0.2 ms 라(3.1) 화면으로 옮기지 않는다 — 옮기면 걸러 내는 판단이 두 자리가 된다 |

**(다) 홀더가 아닌 값**

| 값 | 지금 | 경계에서 |
|---|---|---|
| `DesktopBuildVersion.label` | 하나 | **둘** — 화면의 판과 코어의 판. 첫 인사에서 주고받는다(6.14) |
| `diagnosticLogPathLabel`(`Main.kt:73`) | 하나 | **둘** — 화면의 진단 기록(윈도우 경로)과 코어의 것(WSL 경로, 화면이 `\\wsl.localhost\…` 로 바꿔 보여준다). 오류 자리마다 둘을 함께 보인다 |
| `managedEngineDirectory()`(`Main.kt:173`) | 화면이 홈과 `os.name` 을 읽는다 | 코어가 첫 인사에 싣는다. 엔진의 자리는 코어 OS 의 사실이다 |
| `chooseWorkDirDirectory(window)`(`Main.kt:232`) | 화면 | 화면에 남는다(원칙 19) |
| 테마 선택(`Main.kt:187`) | 화면 | 화면에 남는다 |

### 6.3 넘길 수 있는 것과 없는 것

`terminal/chat/` 의 타입들을 직렬화 관점에서 갈랐다.

| 타입 | 넘길 수 있는가 | 하는 일 |
|---|---|---|
| `ChatEntry` 와 그 갈래(`ChatEntry.kt`) | ✅ 데이터 클래스. `input: JsonObject` 는 이미 직렬화 가능. `requestedAt`·`answeredAt: Instant` 만 직렬화기가 필요 | `@Serializable` 을 단다. `Instant` 는 ISO-8601 문자열 |
| `ToolCallAnswer`·`SubagentRun`·`PermissionAnswer`·`TurnUsage`·`PermissionDenial` | ✅ | 같다 |
| `ChatConversation`(`ChatConversation.kt`) | ✅ `workingDirectory: Path?` 만 걸린다 | 코어 경로 문자열로. `alive`·`resumeFailureSuspected` 는 계산 속성이라 화면에서 다시 계산한다 |
| `OutgoingUserMessage`·`UserMessageSender` | ✅ sealed | |
| `TurnState`·`ChildWaitingFor`·`ChildEndReason` | ✅ enum | |
| `SessionTree`·`ChildSessionNode`·`ChildLifecycle`·`ChildTurnResult` | ✅ `Path`·`Instant` 만 | |
| `SessionTarget`·`PersonOpenedSessionTarget` | ✅ sealed | `{"type":"main"}` · `{"type":"repo","repoName":…}` · `{"type":"child","childId":…}` |
| `PermissionCardChoice` | ✅ sealed, `JsonObject` | |
| `ChatScreenState` | △ `ChatSessionEntryFailed.failure` 가 `TerminalSessionFailure`(예외) | 갈라진다(6.8). 실패는 값으로 |
| `TerminalSessionFailure`·`WorkDirObservationFailure`·`EngineInstallationFailure`·`ClaudeAuthenticationFailure` | ❌ `Throwable` 의 하위 — `cause` 를 쥔다 | **`FailureView(kind, message, guidance)`** 로 바꿔 넘긴다. `kind` 는 갈래 이름이라 화면이 갈래별 문구를 고를 수 있다. 원인 사슬은 코어의 진단 기록에 남는다 |
| `ChildSessionGateQuestion`(`ChatSessionStateHolder.kt:1400`) | ❌ 데이터 클래스가 아니다. 맵의 열쇠가 객체 정체성이다(`gateDecisions`) | 코어가 `gateQuestionId` 를 짓는다. `ChildSessionGate`(`ChildSessionCommandSource.kt:74`, 데이터 클래스)는 그대로 넘긴다 |
| `HeldChatSession`(`HeldChatSession.kt`) | ❌ `OpenedChatSession`(프로세스)·`AppSocketChannel` | 대상만 넘긴다 |
| `AskedPermission`(`PermissionRequest.kt:57`) | ❌ `ConnectionAnswer`(`CompletableDeferred` + 연결 감시) | 코어에 남는다. 화면은 `ChatEntry.PermissionAsked` 를 보고, 답은 `(target, toolUseId)` 로 |
| `AskedSessionTreeRequest`·`PendingParentQuestion`·`AwaitRequest` | ❌ 소켓 연결·`Job` | 코어에 남는다. 화면은 트리의 `waitingFor` 로 본다 |
| `ChatSessionEvent`(`ChatSessionEvent.kt`) | ✅ 이지만 **넘기지 않는다** | 4.7 |
| `WorkDirSnapshot`·`RepoSnapshot` | ✅ 경로가 이미 `String` 이다(`WorkDirSnapshot.kt:12`) | 그대로 |
| `RepositoryCloneState` | ✅ | 그대로 |

**하나의 모델을 쓴다.** 넘길 수 있는 타입들은 `protocol` 모듈로 옮겨 `@Serializable` 을 달고, 코어도 화면도 그 클래스를 그대로 쓴다. 코어 안의 모델과 선 위의 모델을 따로 두고 사이에 옮겨 적는 층을 두지 않는다 — 옮겨 적는 층은 한 필드를 더할 때마다 세 자리를 고치게 하고, 그중 하나를 잊으면 그 필드만 조용히 화면에 안 온다. 값으로 바꿔야 하는 것은 위 표의 ❌ 다섯 줄뿐이다.

### 6.4 프로토콜 — 틀(frame) 여섯

**줄 단위 JSON 이다.** 줄 하나가 틀 하나다. 코어는 틀 하나를 쓸 때마다 flush 한다(3.1). 코어의 stdout 에는 틀 말고 아무것도 쓰지 않는다 — 진단은 파일과 stderr 로 간다.

```
화면 → 코어
{"frame":"hello","protocol":1,"screenVersion":"0.15.0","screenOs":"windows"}
{"frame":"command","id":"c-41","command":{"type":"send_user_message","target":{"type":"main"},"text":"…"}}

코어 → 화면
{"frame":"welcome","protocol":1,"coreVersion":"0.15.0","coreOs":"linux","wslDistro":"Ubuntu-24.04",
 "coreDiagnosticLog":"/home/yawn/.local/state/kyuchestration/core.log","engineDirectory":"/home/yawn/.local/share/kyuchestration/engine"}
{"frame":"snapshot","seq":1200,"part":{…},"last":false}         ← 여러 줄, 6.6
{"frame":"snapshot","seq":1200,"part":{…},"last":true}
{"frame":"change","seq":1201,"change":{"type":"conversation_tail","target":{"type":"child","childId":"child-1"},"fromIndex":42,"entries":[…]}}
{"frame":"reply","id":"c-41","outcome":"accepted"}
{"frame":"reply","id":"c-42","outcome":"rejected","reason":"question_already_answered"}
{"frame":"bye","reason":"replaced_by_another_screen" | "core_shutting_down" | "protocol_mismatch"}
```

| 판단 | 근거 |
|---|---|
| **겉 열쇠가 `kind` 가 아니라 `frame`** | 앱 소켓은 `kind` 로 요청을 가르고, **`kind` 가 없는 줄을 승인 물음으로 읽는다**(`AppSocket.kt:345`, session-tree 6.4). 화면 틀이 어떤 실수로 앱 소켓에 닿아도 승인 물음으로 읽히지 않게, 그리고 사람이 로그를 볼 때 두 규약이 한눈에 갈리게 열쇠 자체를 다르게 둔다 |
| **소켓도 다르다** | 화면 ↔ 코어는 `core.sock` 하나(5 단계), 앱 소켓은 세션마다 하나다(`AppSocketLocation.kt`). 같은 디렉토리(`$XDG_RUNTIME_DIR/kyuchestration/`)에 놓되 파일이 다르다 |
| 명령의 이름이 앱 소켓의 `kind`(`spawn`·`await`·`send`·`end`·`ask_parent`·`list` — `SessionTreeRequest.kt:280~285`)와 겹치지 않는다 | `send_user_message`·`end_session` 처럼 목적어까지 적는다. 같은 낱말이 두 규약에서 다른 뜻이면 사람이 헷갈린다 |
| **`reply` 는 "받았다/거절했다" 까지만** | `accepted` 는 코어가 그 명령을 받아들였다는 뜻이지 일이 끝났다는 뜻이 아니다. 결과는 언제나 `change` 로 온다 — 결과가 오는 길이 둘이면 화면이 둘을 맞춰야 한다. `rejected` 는 낡은 명령(이미 답한 물음, 끝난 세션, 다른 화면이 먼저 답함)을 화면이 알 수 있게 한다 |
| `seq` | 코어가 낸 변화마다 1 씩 는다. 스냅샷은 그것을 찍은 `seq` 를 단다. 화면은 `seq` 가 하나라도 건너뛰면 다시 붙어 스냅샷을 받는다 — 거울이 조용히 어긋나지 않게 |
| `protocol` 은 정수 하나 | 6.14 |
| **(2 단계 정정)** 지금 싣는 필드 | `hello { protocol, screenVersion }` · `welcome { protocol, coreVersion, coreDiagnosticLog, engineDirectory }` · `bye { reason: protocol_mismatch }`. `screenOs` · `coreOs` · `wslDistro` 는 쓰는 자리(3 단계의 경로 변환 · 진단 기록 경로)가 생길 때 더한다 — 필드를 더하는 변경은 판을 올리지 않는다. `bye` 의 나머지 까닭 둘은 5 단계의 것이다 |
| **(2 단계 정정)** 낡은 답의 `rejected` 사유 | `question_already_answered`(승인 카드에 이미 답이 적힘 · 거둬짐, 또는 열린 관문 물음에 그 id 가 없음) · `question_not_found`(그 대상의 대화에 그 승인 카드가 없음). 코어가 명령을 부르기 전에 화면이 본 것과 같은 상태로 가린다 |

### 6.5 변화를 보내는 법 — `StateFlow` 를 그대로 두고, 코어 안의 게시자가 변화로 바꾼다

**상태 홀더는 바꾸지 않는다.** 지금처럼 `StateFlow` 를 내놓는다. 코어 안에 **`ScreenChangePublisher`** 하나를 두어 그 흐름들을 구독하고, 마지막으로 화면에 보낸 값과 견주어 변화를 만든다. 화면 쪽에는 **`CoreMirror`** 가 변화를 받아 같은 모양의 `StateFlow` 를 다시 세운다 — Compose 화면은 홀더 대신 거울을 `collectAsState` 하고, 그 밖의 코드는 거의 그대로다.

| 흐름 | 변화의 모양 | 근거 |
|---|---|---|
| 엔진 설치 · 인증 · 대시보드 · 초기화 · 클론 · 하위 트리 · 관문 물음 · 보유 세션 대상 · 세션 진입 상태 | **통째로** — `{"type":"engine_installation","state":{…}}` | 작다. 견주는 비용과 읽는 비용이 가장 적다(4.6 의 절반) |
| 대화(세션마다) | **좁은 변화 셋** | 크고, 거의 꼬리에서만 바뀐다 |

대화의 변화 셋:

```
conversation_header   { target, turnState, pendingUserMessages, totalCostUsd, usageWindows,
                        endedExitCode, modelName, conversationId, resumedConversationId, workingDirectory }
conversation_tail     { target, fromIndex, entries: [...] }       ← fromIndex 부터 끝까지를 이것으로 바꾼다
streaming_text        { target, blockOrdinal, append: "…" } | { target, blockOrdinal, replace: "…" | null }
conversation_removed  { target }
```

| 판단 | 근거 |
|---|---|
| `entries` 는 **같은 앞부분을 건너뛰고 나머지를 바꾼다** | 대화는 꼬리에 붙고, 바뀌는 것도 꼬리의 몇 줄이다(도구 카드에 답이 오고, 승인 카드에 답이 적힌다). 앞에서부터 `===`(같은 객체)로 견주면 대부분 참조 비교로 끝난다 — 홀더가 `copy` 로 고치므로 안 바뀐 줄은 같은 객체다 |
| 흐르는 글은 **덧붙이기**로 | 글자가 흐를 때마다 `streamingText` 전체를 보내면 길이의 제곱으로 는다. 앞 값이 새 값의 앞부분이면 덧붙인 것만 보낸다. 아니면(블록이 바뀜) 통째로 |
| **`StateFlow` 의 합치기(conflation)를 그대로 쓴다** | 화면이 느리면 중간 상태를 건너뛰고 최신 상태로 간다. 상태를 보내는 것이지 사건 기록을 보내는 것이 아니라서 잃는 것이 없다 — 전사의 사건 순서는 `entries` 안에 이미 있다(원칙 14) |
| 게시자는 **한 줄로 돈다**(병렬 1) | "마지막으로 보낸 값" 을 쥐는 자리가 하나여야 스냅샷과 그 뒤 변화가 같은 기준선에서 나온다 |
| 화면이 밀리면 다시 맞춘다 | 내보낼 틀이 쌓여 한도를 넘으면 그 화면의 변화를 버리고 스냅샷부터 다시 보낸다. 한도는 v1 값으로 4 MiB |

**거절한 다른 길**: 홀더마다 "변화 사건" 을 직접 내게 고치는 것. 홀더 여섯의 모든 `mutableState.value = …` 자리(챗 홀더만 29 군데)에서 사건을 함께 내야 하고, 하나를 빠뜨리면 그 변화만 화면에 안 온다. 견주기는 그 자리를 하나로 줄인다.

### 6.6 큰 챗 로그 — 스냅샷을 나눠 보낸다

**붙는 순간 스냅샷을 한 번 받고, 그 뒤로 변화를 받는다.** 붙을 때마다 언제나 새 스냅샷이다 — "마지막으로 본 `seq` 이후만" 을 주는 이어받기는 두지 않는다. 그러려면 코어가 지난 변화를 쌓아 둬야 하는데, 3.1 이 10 MB 를 0.3 초에 나르는 것을 보였으므로 그 복잡함의 값이 아직 없다.

| 판단 | 근거 |
|---|---|
| 스냅샷은 여러 틀로 — 작은 상태 하나, 그다음 대화마다 머리 하나와 `entries` 묶음 여럿, 마지막 틀에 `"last":true` | 한 줄이 수십 MB 가 되면 화면의 줄 읽기가 그만큼의 버퍼를 잡고, 그 줄이 다 올 때까지 다른 틀(새 승인 물음)이 막힌다 |
| 묶음 하나는 **1 MiB 안쪽** | 3.1 에서 1.26 MB 왕복이 65 ms. 묶음 하나가 화면을 그만큼만 붙잡는다 |
| 줄 하나(`ChatEntry` 하나)는 쪼개지 않는다 | 도구 결과 하나가 1 MiB 를 넘으면 그 틀만 크다. 쪼개면 반쪽짜리 카드를 그리는 길이 생긴다 |
| 화면은 `last` 를 받기 전까지 거울을 바꾸지 않는다 | 반쯤 온 스냅샷을 그리면 대화가 잘려 보인다 |
| 오래된 줄을 나중에 받는 쪽지(페이징)는 두지 않는다 | 실제로 느려지는지 보고 정한다 — 8 절 2 |

### 6.7 물음 → 답이 두 프로세스를 건넌다

| 물음 | 물음이 사는 곳 | 화면이 보는 것 | 답 | 화면이 답하기 전에 죽으면 |
|---|---|---|---|---|
| **승인 카드**(chat-ui 5.4) | 코어의 `askedPermissions`(`AskedPermission` — 앱 소켓 연결을 쥔다) | 대화의 `ChatEntry.PermissionAsked`(답이 `null`) | `answer_permission { target, toolUseId, choice }` | 물음은 그대로 남는다. `claude` 는 기다린다(chat-ui 5.4.1 — 승인에 상한이 없다). 다음 화면의 스냅샷에 그 카드가 답 없이 선다 |
| **관문**(session-tree 6.9) | 코어의 `gateDecisions` — **열쇠를 객체에서 `gateQuestionId` 로** | `gate_questions` 의 맨 앞 | `answer_gate { gateQuestionId, approved }` | 같다. `spawn_session` 은 계속 기다린다 |
| 브라우저 로그인 코드 | 코어의 `runningBrowserLogin`(`claude auth login` 프로세스) | `ClaudeAuthenticationState.BrowserLoginWaitingForCode(authorizationUrl)` | `submit_browser_login_code { code }` | 코어 쪽에는 코드를 기다리는 상한이 없다(`ProcessClaudeCliAuthentication.kt` 의 120 초는 코드를 넣은 뒤 교환의 상한이다). `claude` 자신이 얼마나 기다리는지는 재지 않았다. 다음 화면이 같은 상태를 본다 |
| `ask_parent`(session-tree 6.5) | 코어 | 하위 트리의 `waitingFor: parent` | 사람이 답하지 않는다(session-tree 6.5 넷째 줄) | 바뀌지 않는다 |

**두 화면이 같은 물음에 답하는 일**은 5 단계 이후에도 생기지 않는다 — 화면은 한 번에 하나다(6.13). 그래도 낡은 답은 생긴다: 화면이 답을 보낸 순간 물음이 이미 거둬졌을 수 있다(`claude` 가 도구 호출을 거둠 → `PermissionAnswer.Withdrawn`, `ChatSessionStateHolder.kt:1146`). 코어는 그 답을 `rejected` 로 돌려준다. 지금은 `?: return` 으로 조용히 버리는 자리(`:1163`)다 — 같은 판단을 이제 화면이 알 수 있게 말로 한다.

### 6.8 화면의 상태 — 보고 있는 세션은 화면의 것

지금 `ChatSessionStateHolder.state` 는 **화면에 보이는 세션 하나의 대화**만 내놓는다(`ChatScreenState.ChatOnScreen`). 대화들의 맵(`conversations`)은 홀더 안에 숨어 있고, 그것을 고치는 자리 `updateConversation`(`:1331`)이 보이는 세션일 때만 `state` 를 고친다. 그리고 명령 넷이 "보이는 세션" 을 암묵의 대상으로 쓴다 — `sendUserMessage`(`:278`)·`interruptOnScreenTurn`(`:323`)·`endOnScreenSession`(`:380`)·`answerOnScreenPermission`(`:1161`).

**두 안**

| 안 | 내용 | 판단 |
|---|---|---|
| (가) **보이는 세션을 화면으로 옮긴다** | 코어는 **모든 대화**를 세션별로 내놓고(6.5 의 `conversation_*`), 세션마다 진입 상태(`없음 · 띄우는 중 · 실패(FailureView)`)를 내놓는다. 화면이 어느 것을 볼지 정한다. 명령은 대상을 명시한다 | **추천.** 원칙 21 — 보는 방식은 화면의 것이다. 세션을 오갈 때 왕복이 없다. 6.2 의 `showChildSession` 이 화면 안의 일이 된다 |
| (나) 보이는 세션을 코어에 둔다 | 화면이 `show_session { target }` 을 보내고 코어가 그 대화만 내놓는다 | 세션을 옮길 때마다 대화 전체(MB)가 건너온다. 화면이 다시 붙으면 코어가 기억하는 "보던 세션" 이 사람이 마지막에 본 것과 다를 수 있다 |

**(가) 가 바꾸는 동작이 하나 있다.** 지금 `enterSession` 은 세션을 띄우는 사이 사람이 다른 세션을 고르면 막 뜬 프로세스를 끝낸다(`:254`~`:258`, `latestSessionEntryRequestId`). 보는 것이 화면으로 가면 코어는 "사람이 다른 데로 갔다" 를 모른다. **띄운 세션은 그대로 둔다** — 보유 세션 목록에 서고, 사람이 그 줄을 누르면 이미 떠 있다. 끝내던 이유(앞 세션을 보지 않으니 버린다)는 앱이 세션을 여럿 보유하게 된 뒤로 약해졌다. 사용자가 확인할 동작 변화다 — 8 절 3.

하위를 사람이 끝내면 화면이 메인으로 돌아가던 것(`:423`)도 화면의 일이 된다.

### 6.9 경로 — 화면 경로와 코어 경로

**선 위의 경로는 언제나 코어 경로다**(리눅스·맥의 POSIX 경로). 화면 경로가 선에 오르는 자리는 하나 — `open_workdir { hostPath }` 다.

| 화면이 고른 것 | 코어가 푸는 법 | 결과 |
|---|---|---|
| `\\wsl.localhost\Ubuntu-24.04\home\yawn\work` | `wslpath -u` | `/home/yawn/work` |
| `\\wsl$\Ubuntu-24.04\…` | 같다(3.3 — 옛 형식도 푼다) | 같다 |
| `\\wsl.localhost\docker-desktop\…`(다른 배포판) | `wslpath -u` 가 실패한다(3.3) | `rejected` — *"이 코어는 Ubuntu-24.04 에 있습니다. 다른 배포판의 폴더는 열 수 없습니다"* |
| `C:\Users\maxim\work` | `wslpath -u` → `/mnt/c/Users/maxim/work` | 받아들인다. 느릴 수 있다는 것은 8 절 6 |
| 맥·리눅스에서 고른 `/Users/…` | 그대로 | 그대로 |

| 판단 | 근거 |
|---|---|
| **코어가 푼다** | 화면은 자기 OS 의 문자열을 줄 뿐이다. 푸는 규칙(자동 마운트 자리 `/mnt/c` 는 `wsl.conf` 의 `[automount] root` 로 바뀐다)은 코어 OS 의 사실이다. 화면이 문자열을 잘라 풀면 그 설정을 모른다 |
| `wslpath` 를 부른다, 직접 자르지 않는다 | 대소문자·두 형식·자동 마운트를 `wslpath` 가 이미 안다(3.3). 코어가 WSL 안에 있는지는 `WSL_DISTRO_NAME` 으로 안다. 맥·리눅스에서는 이 갈래를 지나지 않는다 |
| 화면이 코어 경로를 **보여줄** 때 | 그대로 보인다(`/home/yawn/work`) — 사람이 WSL 터미널에서 쓰는 이름이다. 화면이 윈도우 경로로 바꿔야 하는 자리는 "탐색기에서 열기" 같은 OS 기능뿐이고 지금은 없다. 진단 기록 경로(6.2 (다))만 `\\wsl.localhost\<배포판>` + 경로로 잇는다 — 배포판 이름은 `welcome` 이 준다 |

### 6.10 OS 기능 — 화면의 것

| 기능 | 지금 | 갈라진 뒤 |
|---|---|---|
| 워크디렉토리 고르기 | `WorkDirDirectoryChooser.kt` — 맥은 `FileDialog`, 그 밖은 `JFileChooser` | 화면. **윈도우 갈래가 생긴다** — 윈도우는 네이티브 폴더 선택 창이 "Linux" 아래 WSL 폴더를 보여주므로 그쪽을 쓰고 싶다. AWT `FileDialog` 는 윈도우에서 폴더를 고르지 못하므로(`WorkDirDirectoryChooser.kt:19`) `JFileChooser` 로 시작하고, 네이티브 창은 3 단계에서 고른 경로 형식을 확인한 뒤 정한다(3.8) |
| 클립보드 | `ClaudeAuthenticationScreen.kt:304` — 인증 주소 복사 | 화면. 그대로 |
| 브라우저 열기 | `claude auth login` 이 스스로 연다(코어 쪽 OS) | **화면이 연다.** WSL 의 `claude` 가 윈도우 브라우저를 여는지는 재지 않았다(3.8). 상태가 `BrowserLoginWaitingForCode(authorizationUrl)` 로 바뀌면 화면이 자기 OS 의 브라우저로 그 주소를 연다. `claude` 가 이미 열었으면 같은 주소가 두 번 열리는데, 그것이 열리지 않는 것보다 낫다 |
| 진단 기록 | 앱 하나에 파일 하나(`diagnostics/`) | **둘.** 화면은 자기 실패(코어를 못 띄움·프로토콜 깨짐)를, 코어는 지금 기록하는 모든 것을 적는다. 화면이 코어를 못 띄운 실패는 코어가 없으므로 화면만 적을 수 있다 |
| 창 닫기 확인 · 알림 | 없다 | 화면. 5 단계에서 "세션 N 개가 코어에서 계속 돕니다" 를 처음 한 번 알린다 |

### 6.11 비밀이 경계를 건넌다

명령 둘이 비밀을 싣는다 — `use_claude_token { token }` 과 `register_token_profile { profileName, token }`.

| 판단 | 근거 |
|---|---|
| 그대로 싣는다 | 통로는 화면이 띄운 자식의 stdio 다. 윈도우 파이프 → `wsl.exe` → WSL 프로세스. 같은 사용자의 프로세스 사이이고 네트워크를 지나지 않는다(4.5 가 TCP 를 기각한 이유의 뒷면). 지금도 같은 토큰이 앱 → `kyu` 의 stdin 으로 간다(`KyuCliClaudeTokenStore`) |
| **틀을 기록하는 모든 자리가 비밀 필드를 가린다** | 진단 기록에 틀을 적는 날이 온다(프로토콜 결함을 원격으로 보려면). 그때 `token` 이 평문으로 남지 않게, 비밀을 싣는 명령 타입은 `protocol` 모듈이 표시하고 기록기는 그 필드를 `"***"` 로 적는다. 시험으로 고정한다 |
| 화면은 토큰을 쥐지 않는다 | 입력 칸을 비우면 끝이다. 저장은 지금처럼 코어가 `kyu` 에게 맡긴다 — WSL 에서는 `secret-tool` 이 없어 설정 파일 폴백이 되고, 그 사실을 화면이 알린다(`tokenWouldBeStoredInPlaintext`, 지금 그대로) |

### 6.12 코어를 띄우는 한 함수

**OS 가 갈리는 자리는 여기 하나다**(원칙 4·20).

```kotlin
// ui 모듈. 돌려준 argv 를 ProcessBuilder 로 띄우고, 그 stdio 위에서는 OS 를 모른다.
fun coreLaunchCommand(place: CorePlace, verb: CoreVerb): List<String> = when (place) {
    is CorePlace.Local -> listOf(place.coreExecutable.toString(), verb.argument)
    is CorePlace.WslDistribution -> listOf(
        "C:\\Windows\\System32\\wsl.exe", "-d", place.distributionName,
        "--cd", "~", "-e", ".local/bin/kyuchestration-core", verb.argument,
    )
}
```

| 판단 | 근거 |
|---|---|
| 셸을 거치지 않는다 | 3.1 (나) — PowerShell 이 바이트를 고친다. 3.7 — 윈도우 명령줄에서 `"` 가 사라진다 |
| `--cd ~` + 상대 경로 | 3.7 — 화면은 리눅스 홈을 모르고, `wsl.exe -e` 의 PATH 에는 `~/.local/bin` 이 없다. 셸 없이 실행 파일에 닿는 길이 이것이다 |
| `wsl.exe` 의 절대 경로 | 사용자의 PATH 에 다른 `wsl.exe`(옛 `bash.exe` 시절 래퍼 등)가 앞에 있어도 갈리지 않게 |
| 인자는 동사 하나(`serve --stdio` 또는 `attach`) | 나머지(판, 화면 OS)는 `hello` 로 보낸다 |
| **(2 단계 정정)** 지금의 함수 | `Local` 갈래만 있다 — `app` 모듈의 `coreLaunchCommand()` 가 자기 실행기(`jpackage.app-path`)에 `core serve --stdio` 를 붙이거나, 실행기 없이 떴으면(`./gradlew run`) 같은 java · 클래스패스 · 앱 리소스 자리로 같은 main 을 부른다. `WslDistribution` 은 3 단계에서 더한다 |
| 코어의 `claude`·`kyu` 찾기 | 코어가 한다 — 지금 앱의 `LoginShellSearchPath.kt` 그대로(3.7 이 그것이 여전히 필요함을 보였다) |
| 윈도우 JVM 이 `wsl.exe` 를 찾지 못하면 | "WSL 이 설치되어 있지 않습니다" 화면(6.17). 지금의 `WindowsNeedsWsl` 이 화면 쪽으로 옮겨 온 모양이다 |

### 6.13 코어 수명

**단계 2~4 (데몬 없음)**: 화면이 `serve --stdio` 를 띄운다. 코어는 stdin EOF 에 모든 세션을 끝내고(지금 `endAllSessions` 의 순서 — 하위 먼저, 상위 나중, session-tree 6.13) 끝난다. 화면이 크래시해도 코어가 EOF 를 받는다(3.5 의 N1 — 중계 없이 직결이면 더 곧다). **세션은 화면과 생사를 같이한다** — 지금과 같다.

**5 단계 (데몬 + `attach`)**

| 질문 | 답 | 근거 |
|---|---|---|
| **누가 띄우나** | `attach` 가. 화면은 언제나 `attach` 만 띄운다. `attach` 는 `core.sock` 에 붙어 보고, 아무도 안 받으면 데몬(`serve`)을 떼어 띄운 뒤 소켓이 설 때까지 기다렸다 붙는다 | 화면은 데몬이 있는지 몰라도 된다. 처음이든 다시 붙든 같은 동사 |
| 떼어 띄우는 법 | `setsid` 로 세션을 떼고 **stdio 를 `/dev/null`·기록 파일로 돌린 채** 띄운다. `systemd --user` 가 있으면 `systemd-run --user --collect --unit=kyuchestration-core` 를 먼저 쓴다 | 3.2 — `wsl.exe` 의 명령이 끝나면 같은 세션에 `SIGHUP`. stdio 를 물린 채 떼면 7/10. 둘 다 10/10 은 `/dev/null` + `setsid`, `systemd-run`. `attach` 는 소켓이 선 것을 본 뒤에야 다음 걸음을 가므로 "준비를 확인한 뒤 끝난다" 가 저절로 지켜진다. JVM 에는 `setsid()` 를 부르는 표준 API 가 없어 `setsid` 실행 파일을 거친다 |
| 둘이 동시에 띄우려 하면 | `$XDG_RUNTIME_DIR/kyuchestration/core.lock` 에 `flock`. 잡은 쪽만 띄우고 나머지는 소켓을 기다린다 | 화면 둘을 거의 동시에 연 경우 |
| **화면은 몇 개** | **한 번에 하나.** 새 `attach` 가 붙으면 코어는 옛 연결에 `bye { replaced_by_another_screen }` 를 쓰고 닫는다 | 3.5 의 S2 — 크래시한 화면의 중계가 남아도 다음 화면이 막히지 않는다. 둘을 함께 보여주는 것은 8 절 4 |
| **언제 끝나나** | ① 사람이 "코어 끝내기"(모든 세션을 끝낸다)를 고른다 → `shutdown_core` ② **붙은 화면이 없고 살아 있는 세션도 없는 채로 일정 시간**(v1 값 10 분)이 지나면 스스로 끝난다 | ② 가 좀비 방지다. 세션이 살아 있는 동안에는 화면이 없어도 남는다(원칙 5 재개정). 세션이 없으면 남을 이유가 없다 — 메모리(3.6)만 쥔다. 10 분은 화면을 껐다 다시 켜는 사이를 넉넉히 넘기려는 값이다 |
| 창을 닫으면 | **떼기만 한다**(`attach` 의 stdin 을 닫는다 → 3.5 의 N2 처럼 코어가 EOF 를 보고 그 연결만 놓는다). 처음 한 번 "세션이 계속 돕니다 — 끝내려면 메뉴의 코어 끝내기" 를 알린다 | 사용자 요구 *"화면이 죽어도 코어는 살아야"* 를 닫기에도 같게 적용한다. 닫기와 크래시가 다르게 굴면 사람은 어느 쪽이 세션을 지키는지 외워야 한다 |
| 화면이 크래시하면 | `attach` 의 stdin 이 EOF → 코어가 그 연결을 놓는다 | 3.5 의 N1. `attach` 가 EOF 를 넘기고 끝나는 것을 시험으로 고정한다 |
| `wsl.exe` 만 강제로 죽으면 | `attach` 는 stdout 에 쓸 때 `SIGPIPE`/`EPIPE` 를 받는다. 그것을 EOF 와 같이 다룬다 | 3.2 의 B — 신호는 오지 않고 파이프만 끊긴다 |
| **배포판이 멈추면** | 코어도 세션도 함께 죽는다. 다음에 뜬 코어가 `--resume` 으로 대화를 이어간다(원칙 5 재개정의 뒷문장) | 3.8 의 첫 줄. **화면이 없는 동안 배포판이 스스로 멈추는지가 이 설계의 가장 큰 미확인이다.** 멈춘다면 "화면 없이 사는 코어" 는 사용자가 WSL 터미널을 하나라도 열어 둔 동안에만 성립한다. 5 단계에서 재고, 멈춘다면 8 절 1 의 안을 고른다 |

### 6.14 판(버전) 불일치

| 경우 | 어떻게 안다 | 어떻게 한다 |
|---|---|---|
| 화면과 코어의 `protocol` 이 다르다 | `hello`/`welcome` | 코어가 `bye { protocol_mismatch }` 로 닫는다. 화면은 어느 쪽이 낡았는지(`screenVersion` · `coreVersion`)를 보이고 할 일을 하나로 적는다 — 코어가 낡았으면 WSL 에서 돌릴 설치 명령(복사 버튼), 화면이 낡았으면 msi 받기 |
| **설치된 코어는 새것인데 떠 있는 데몬은 옛것**(사용자가 코어를 갱신했다) | `attach`(새 실행 파일)가 데몬의 `welcome.coreVersion` 을 본다 | 데몬에 살아 있는 세션이 없으면 `attach` 가 데몬을 끝내고 새로 띄운다. 있으면 화면에 *"옛 코어(0.15.0)에서 세션 2 개가 돌고 있습니다 — 끝내고 갱신 / 그대로 쓰기"* 를 묻는다(그대로 쓰기는 `protocol` 이 같을 때만) |
| `protocol` 의 판 규약 | 정수 하나. 필드를 더하는 변경은 올리지 않고(모르는 필드는 무시), 뜻이 바뀌거나 필드가 빠지면 올린다 | `internal/workdir/conversations.go` 의 `schemaVersion` 규약과 같은 선 |

**화면과 코어는 같은 태그에서 같은 판으로 나간다**(6.16). 판이 다른 것은 둘 중 하나를 갱신하지 않은 경우뿐이다.

### 6.15 Gradle 모듈 — `protocol` · `core` · `ui`

`desktop/` 는 지금 모듈 하나다(`desktop/settings.gradle.kts`). 셋으로 가른다.

```
desktop/
├─ settings.gradle.kts           include(":protocol", ":diagnostics", ":core", ":ui", ":app")   ← (2 단계 정정)
├─ protocol/   kotlinx-serialization-json + kotlinx-coroutines.  틀(frame)·명령·변화·화면 모델(@Serializable)·FailureView
│              · 경계 인터페이스 ScreenFacingCore
├─ core/       protocol + kotlinx-coroutines.  Compose 없음.
│              terminal/ · terminal/chat/ · terminal/kyucli/ · kyu/ · engine/ · claudeauth/ · workdir/ ·
│              repoclone/ · dashboard/ · initialization/ · platform/ · diagnostics/(코어 몫)
│              + ScreenChangePublisher · CoreMain(serve · attach)
└─ ui/         protocol + Compose.  화면 파일 전부 · theme/ · WorkDirDirectoryChooser · CoreMirror ·
               coreLaunchCommand · diagnostics/(화면 몫)
```

**(2 단계 정정)** 실제로는 모듈이 다섯이다.

| 모듈 | 무엇 | 근거 |
|---|---|---|
| `diagnostics` | 진단 기록(닫힌 사건 갈래 · 파일 · 미처리 예외) · `runningOnMac` · 판 라벨(`BuildVersion`) | 화면과 코어가 각자 자기 파일(`desktop.log` · `core.log`)에 적지만 같은 사건 갈래를 쓴다. 둘로 베끼면 "기록에 비밀이 들어가지 않는가" 를 두 자리에서 답해야 한다 |
| `app` | 실행기 하나(`DesktopEntry`)와 설치 패키지 · 동봉 엔진 | 패키지에 실행 파일을 하나밖에 둘 수 없다(6.16 정정). 첫 인자 `core` 면 코어, 아니면 화면 — 둘을 함께 아는 것은 이 자리뿐이라 `ui` 는 `core` 를 의존하지 않는다(시험만 의존) |

| 판단 | 근거 |
|---|---|
| `core` 는 Compose 를 의존하지 않는다 | 3.6 — 클래스패스의 86 % 가 화면 것. 그리고 의존 방향을 빌드가 막는다 — 코어 코드가 Compose 타입을 쓰면 컴파일이 안 된다(헥사고날 방향을 모듈 의존으로 강제하는 것과 같은 자세) |
| `ui` 는 `core` 를 의존하지 않는다 | 원칙 20. 화면이 코어 클래스를 import 할 수 있으면 같은 JVM 지름길이 생긴다 |
| `protocol` 에 화면 모델을 둔다 | 6.3 의 "하나의 모델". `ChatEntry`·`ChatConversation`·`SessionTree` 등이 여기로 온다 |
| **(2a 정정)** `protocol` 은 kotlinx-coroutines 도 의존한다 | 경계 인터페이스 `ScreenFacingCore` 가 상태를 `StateFlow` 로 내놓는다. 코어의 구현과 화면의 거울(`CoreMirror`)이 같은 인터페이스를 지키려면 그것이 둘이 함께 보는 `protocol` 에 있어야 한다(PR #77) |
| 동봉 엔진(`buildBundledEngine`)은 `core` 의 패키징으로 | 엔진을 부르는 것은 코어다. 윈도우 화면 패키지(msi)는 엔진을 담지 않는다 — 지금 `hostBundledEngineTarget` 이 윈도우에서 던지는 것(`build.gradle.kts` 의 그 자리)이 `ui` 에 걸리지 않게 된다. **(2 단계 정정)** 지금은 `app/build.gradle.kts` 에 있다 — 설치 패키지(jpackage)를 내는 모듈이 `app` 하나라 엔진을 앱 리소스로 넣는 자리도 거기다. 코어가 따로 패키징되는 4 단계(WSL 의 코어 이미지)에서 옮긴다 |

**시험이 가는 곳** — 지금 `desktop/src/test` 의 77 개 파일.

| 시험 | 가는 곳 |
|---|---|
| `terminal/chat/` 의 홀더·소켓·접기·스트림 시험(`ChatSessionStateHolderTest`·`ChildSession*StateHolderTest`·`AppSocketTest`·`ChatConversationFoldingTest`·`ChatStreamLineReadingTest`·`ProcessChatSession*Test`·`SessionTree*Test` …) | `core` |
| **실 `claude` 통합 시험**(`RealClaudeChatSessionIntegrationTest`·`RealClaudeChatConversationIntegrationTest`·`RealClaudePermissionBridgeIntegrationTest`·`RealClaudeSessionTreeIntegrationTest`·`RealClaudeToolWaitInterruptIntegrationTest`·`RealClaudeAuthenticationIntegrationTest`) | `core`. 환경 변수(`KYU_BINARY_PATH`·`KYU_CLAUDE_CHAT_INTEGRATION`)를 시험 JVM 에 싣는 설정은 루트 `desktop/build.gradle.kts` 의 `subprojects` 한 자리 — 모듈마다 두면 하나가 빠질 때 그 모듈의 실 claude 시험이 조용히 건너뛴다 |
| **(2a 정정)** `RealClaudeDelegationIntegrationTest` | `ui`. 위임 카드가 읽는 엔진의 답 문서를 화면의 `delegationAnswerOrNull` 로 읽어 보는 시험이라서다 |
| `kyucli/`·`workdir/kyucli/`·`repoclone/`·`claudeauth/`·`engine/`·`kyu/`·`platform/`·`dashboard/`·`initialization/` 시험 | `core` |
| `theme/` 시험 · `ToolCallCardContentTest` · `ChatTurnBadgeTextTest` · `ChatInterruptionEchoTest` · `CodeFenceLanguageNameTest` · `DelegationAnswerReadingTest` · `AskParentAnswerReadingTest` · `SpawnSessionAnswerReadingTest` · `WorkDirDirectoryChooserTest` · `DesktopBuildVersionTest` | `ui` |
| `diagnostics/` 시험 | 둘 다(각자 몫) |
| **새로**: 틀·명령·변화의 직렬화 왕복과 비밀 가리기 | `protocol`. **(2a 정정)** 비밀 가리기(`SecretsStayOffTheScreenFramesTest`)는 `ui` — 진짜 홀더와 거울을 함께 세운다 |
| **(2a 정정)** `FailureViewTest` | `core`. 상태 갈래의 실패 예외(코어의 것)를 `FailureView` 로 바꾸는 시험이라 `protocol` 에서는 그 예외를 볼 수 없다 |
| **새로**: 게시자 → 거울 — 실제 홀더의 `StateFlow` 변화가 거울에 같은 값으로 서는가(스냅샷 + 변화, `seq` 건너뛰기에 다시 맞추기) | 게시자만 보는 왕복(`ScreenChangeRoundTripTest`)은 `core`(`protocol` 의 순수 함수만 쓴다). **(2a 정정)** 진짜 거울까지 세우는 시험(`ScreenPipeRoundTripTest`·`InProcessCoreStartTest`)은 `ui` — `CoreMirror` 가 `ui` 에 있다. 그 시험들이 쓰는 코어 시험대는 `core` 의 testFixtures |
| **새로**: `attach` 가 stdin EOF 를 넘기고 끝나는가, 새 화면이 옛 연결을 밀어내는가 | `core` |

### 6.16 설치와 릴리스

| 플랫폼 | 화면 | 코어 + 엔진 | 설치 |
|---|---|---|---|
| **윈도우 + WSL** | msi(윈도우) — 화면만 | WSL 안 `~/.local/share/kyuchestration/core/` + `~/.local/bin/kyuchestration-core`, 엔진 `~/.local/bin/kyu` | 윈도우: msi. WSL: `install.sh` 확장(아래) |
| 맥 | dmg — 화면 + 코어 + 엔진 | 앱 번들 안 | 지금처럼 dmg 하나 |
| 리눅스(WSLg 포함) | deb — 화면 + 코어 + 엔진 | 패키지 안 | 지금처럼 deb 하나 |

| 판단 | 근거 |
|---|---|
| **WSL 의 코어는 런타임을 품은 앱 이미지(tar.gz)** | `jpackage` 의 리눅스 app-image. WSL 에 자바를 요구하지 않는다 — 지금 deb 가 "앱만 깔면 끝" 인 것(`build.gradle.kts` 의 동봉 엔진 주석)과 같은 자세. 크기는 재지 않았다 |
| `install.sh` 를 넓힌다: `--with-core` 면 `kyu` 와 코어 이미지를 함께 받아 `~/.local` 아래에 둔다. sudo 없음 | 지금 `install.sh` 가 이미 `~/.local/bin` 에 `kyu` 를 둔다(`install.sh:309`). 같은 자리, 같은 인증 순서(무인증 먼저, 거절되면 `GITHUB_TOKEN`) |
| msi 는 `windows-latest` 러너에서 Compose 의 `packageMsi` | `jpackage` 는 자기 OS 용만 만든다 — dmg 잡이 맥 러너를 쓰는 것과 같은 이유(`release.yml` 의 그 주석). WiX 는 Compose 플러그인이 받는 것으로 읽힌다(3.8 — 실측 아님). 사용자 단위 설치(`perUserInstall`)로 관리자 권한을 묻지 않고, 판을 올려 덮어 깔 수 있게 `upgradeUuid` 를 고정한다 |
| 맥·리눅스 패키지 안의 코어 | 화면이 띄울 실행 파일이 패키지 안에 있어야 한다. `jpackage` 의 추가 실행기(`--add-launcher`)로 같은 런타임을 나눠 쓰는 길이 가장 작다. Compose 플러그인이 그것을 여는지는 확인하지 않았다 — 2 단계에서 정한다 |
| **(2 단계 실측)** 맥·리눅스 패키지 안의 코어 | **앱의 실행기 하나를 화면과 코어가 함께 쓴다** — 화면이 `<앱>/bin/Kyuchestration core serve --stdio` 로 다시 띄운다. 다른 길이 막혔다: ① Compose 플러그인 1.12.0 은 `--add-launcher` 를 열지 않는다(`AbstractJPackageTask` 에 그 인자가 없다) ② Compose 가 런타임을 만들 때 `--strip-native-commands` 를 고정으로 넘겨(`createRuntimeImage.args.txt`) 런타임에 `bin/java` 가 없다 — 같은 런타임으로 `java -cp` 할 수 없다 ③ 앱 리소스로 넣은 파일은 deb 에서 `-rw-r--r--` 로 실행 권한을 잃는다 — 코어의 앱 이미지를 리소스로 담아도 그 실행기와 `jspawnhelper` 를 부를 수 없다. 대가: 코어의 클래스패스에 Compose jar 가 함께 실린다(읽히지 않아 RSS 차이는 몇 MB). 4 단계의 WSL 코어 이미지는 따로 만든다 |
| 화면과 코어는 **같은 태그, 같은 판** | `release.yml` 의 `resolve-version` 이 이미 하나의 판을 모든 잡에 나눠 준다. 잡이 둘(윈도우 msi · 리눅스 코어 tar.gz) 는다 |

### 6.17 첫 실행 — 윈도우 화면이 코어를 찾기까지

```
화면이 뜬다
  │
  ├─ wsl.exe 가 없다 ───────────────────────▶ "WSL 이 필요합니다" + 설치 안내(wsl --install)
  │
  ├─ wsl.exe -l -v (UTF-16LE 또는 WSL_UTF8=1) → 배포판 목록, docker-desktop* 은 뺀다
  │     └ 지난번에 고른 배포판이 있으면 그것, 없으면 기본(*) 배포판을 먼저 본다
  │
  ├─ wsl.exe -d D --cd ~ -e .local/bin/kyuchestration-core version
  │     ├ 답한다 ─────────────────────────────▶ attach (또는 단계 2~4 의 serve --stdio)
  │     └ 실행 파일이 없다 ────────────────────▶ "이 배포판에 코어가 없습니다"
  │                                               + WSL 터미널에서 돌릴 설치 명령 한 줄(복사 버튼)
  │                                               + 다른 배포판 고르기 · 다시 보기
  └─ 코어가 붙으면 지금의 갈림길(엔진 설치 → claude 인증 → 워크디렉토리)이 코어의 상태로 이어진다
```

| 판단 | 근거 |
|---|---|
| 고른 배포판은 화면이 기억한다 | 윈도우 쪽의 사실이고, 코어를 찾기 전의 일이다 |
| 설치를 화면이 대신 돌리지 않는다(v1) | 설치 스크립트가 지금 비공개 저장소의 토큰을 묻는다. 그 대화를 `wsl.exe` 너머로 화면이 대신하면 토큰 입력 칸·진행 표시·실패 갈래를 새로 짜야 한다. 명령 한 줄을 복사해 WSL 터미널에서 돌리는 것으로 시작한다 — 8 절 5 |
| 엔진 설치 화면은 코어의 상태 | 코어가 붙은 뒤 엔진이 없으면 지금의 `EngineInstallationScreen` 이 그대로 선다. 엔진은 코어 옆(WSL)에 받는다 |

---

## 7. 구현 단계

각 단계는 PR 하나다. **앞 단계의 완료 확인이 통과하지 않으면 다음 단계를 시작하지 않는다.**

| 단계 | 내용 | 완료 확인 |
|---|---|---|
| **1** | **프로토콜 추출 — 한 프로세스 안에서.** 모듈은 아직 하나다. 챗 홀더의 공개 표면을 대상 명시로 바꾸고(6.8 (가)), 대화들을 세션별로 내놓는다. 관문 물음에 id(6.7). `FailureView`(6.3). 틀·명령·변화의 `@Serializable` 타입과 `ScreenChangePublisher` · `CoreMirror` 를 만들고, **앱이 같은 JVM 안에서 직렬화된 줄을 거쳐서만** 화면을 그리게 한다(메모리 파이프) | 시험: 직렬화 왕복, 비밀 가리기, 게시자 → 거울(실제 홀더). **실측 시나리오**(실 `claude`, 스크래치 워크디렉토리): 메인 챗 · 승인 카드 · 하위 둘 띄우기 · 관문 대화 상자 · 중단이 지금과 같이 돈다. 그 사이 오간 줄을 기록해 큰 대화의 스냅샷 크기를 PR 본문에 적는다 |
| **2** | **코어 분리 — 같은 머신.** Gradle 모듈 `protocol`·`core`·`ui`(6.15). `kyuchestration-core serve --stdio`. 화면이 `coreLaunchCommand(Local)` 로 띄운다. 코어는 stdin EOF 에 세션을 다 끝내고 끝난다(6.13 단계 2~4). 시험이 모듈로 옮겨 간다. 맥·리눅스 패키지에 코어를 넣는 법(6.16) | **실측**: 리눅스(WSLg)·맥에서 1 단계의 시나리오가 프로세스 경계를 넘어 돈다. 화면을 `kill -9` 하면 코어가 EOF 를 보고 모든 `claude` 를 끝낸다(`ps` 에 남는 것 없음). 화면 JVM 과 코어 JVM 의 RSS 를 따로 잰다(3.8). `./gradlew :core:dependencies` 에 Compose 가 없다 |
| **3** | **윈도우 화면 + `wsl.exe` 연결 + 경로.** `coreLaunchCommand(WslDistribution)`, 배포판 목록(UTF-16LE · `WSL_UTF8`)과 고르기, `open_workdir { hostPath }` 를 코어가 `wslpath -u` 로 풀기(6.9), 윈도우 파일 선택기 갈래, 브라우저 열기를 화면으로(6.10). 개발은 WSL 에서 `:core` 를 설치하고 윈도우에서 `:ui:run` | **실측**(윈도우 + WSL, 실 `claude`): 윈도우 창에서 WSL 폴더를 골라 열고 → 메인 챗에 한국어로 말하고 → 승인 카드에 답하고 → 하위 세션을 띄운다. 네이티브 선택 창이 주는 경로 형식을 확인한다(3.8). 다른 배포판 폴더를 고르면 거절 문구. 창을 닫으면 WSL 에 `claude` 가 남지 않는다 |
| **4** | **msi 릴리스.** `release.yml` 에 윈도우 잡(`packageMsi`)과 리눅스 코어 tar.gz 잡, `install.sh --with-core`, 첫 실행 흐름(6.17), README | **실측**: 시험 태그로 워크플로를 돌려 msi·tar.gz 가 나온다. 사용자의 윈도우에 msi 를 깔고, 코어가 없는 배포판에서 안내를 따라 설치한 뒤 3 단계 시나리오가 돈다. 판이 다른 화면·코어를 붙이면 `protocol_mismatch` 화면 |
| **5** | **코어 수명·재접속.** `serve`(데몬)와 `attach`(중계), `core.sock`·`core.lock`, 새 화면이 옛 연결을 밀어냄, 화면 없음 + 세션 없음 10 분에 끝남, `shutdown_core`, 판 갈아 끼우기(6.14), 창 닫기 = 떼기 | **실측**: ① 턴이 도는 중에 윈도우 창을 닫고 → 다시 열면 같은 대화에 그 턴의 끝이 서 있다 ② 화면 JVM 을 작업 관리자로 죽이고 다시 열면 붙는다 — WSL 에 남은 `attach` 없음(`pgrep`) ③ 승인 카드가 뜬 채로 화면을 죽이고 다시 열면 카드가 그대로 있고 답이 간다 ④ 화면 없이 세션 없이 10 분 뒤 코어가 사라진다 ⑤ **모든 `wsl.exe` 가 끝난 뒤 배포판이 멈추는가**를 사용자 머신에서 잰다(3.8 첫 줄) ⑥ 맥에서 떼어 낸 코어가 화면 종료 뒤에 사는가 |

**1 단계가 가장 크다.** 리뷰가 어려우면 둘로 나눈다 — 1a: 챗 홀더의 대상 명시화와 대화 맵 공개(직렬화 없음, 동작 그대로), 1b: 타입·게시자·거울·메모리 파이프.

---

## 8. 열린 질문

1. **배포판이 화면 없이 멈추면.** 6.13 의 마지막 줄. 멈춘다면 후보는 (가) 화면이 없는 동안 코어가 사라질 수 있음을 받아들이고, 다시 뜬 코어가 `--resume` 으로 잇는다(원칙 5 재개정의 뒷문장이 이미 그것이다) (나) 윈도우 쪽에 배포판을 붙잡는 작은 상주 프로세스를 둔다 — 화면이 아닌 프로세스가 윈도우에 하나 더 사는 것이라 무겁다. **(가) 를 먼저 본다.** 5 단계의 실측 ⑤ 가 정한다.
2. **오래된 줄의 페이징.** 6.6. 스냅샷이 실제로 느려지면 꼬리만 먼저 보내고 위로 올릴 때 더 받는 명령을 더한다.
3. **띄우는 중에 다른 세션을 고르면.** 6.8 의 동작 변화 — 지금은 막 뜬 프로세스를 끝내고, 바뀐 뒤에는 그대로 둔다. 사용자 확인이 필요하다.
4. **화면 둘.** 지금은 새 화면이 옛 화면을 밀어낸다(6.13). 윈도우 창과 맥 창이 같은 WSL 코어를 함께 보는 일은 요구가 없다. 생기면 연결마다 게시자를 두면 되고 프로토콜은 바뀌지 않는다.
5. **화면이 코어 설치를 대신 돌릴 것인가.** 6.17. 저장소가 공개되면 토큰 물음이 사라지므로 그때 `wsl.exe -e bash -c` 로 설치 스크립트를 돌리는 길이 단순해진다.
6. **`/mnt/c` 의 워크디렉토리.** 6.9 는 받아들인다. 느리다는 것을 화면이 알려야 하는지는 실제로 쓰는 사람이 생기면 정한다.
7. **중계를 Go 로.** 4.9 (나). `attach` JVM 의 메모리가 문제로 보이면 다시 본다.
8. **코어 하나에 워크디렉토리 여럿.** 지금 앱은 창 하나에 워크디렉토리 하나다(app-owned 열린 질문 6). 코어가 데몬이 되면 "다른 워크디렉토리를 열면 앞의 세션이 끝난다"(`close_workdir`)가 화면을 닫았다 여는 일과 섞인다. 이 설계는 지금의 규칙을 그대로 둔다.

---

## 부록 A. 확인된 사실

| 사실 | 근거 |
|---|---|
| 윈도우 JVM 의 `ProcessBuilder` 가 `wsl.exe -d <배포판> -e <명령>` 으로 띄운 WSL 프로세스와 stdio 로 오간 UTF-8 바이트가 양방향으로 같다. `\r` 이 붙지 않는다. stderr 는 따로 온다 | 2026-10-22 실측 (3.1) |
| 위 통로의 작은 줄 왕복 p50 199 µs, 10.07 MB 한 줄 왕복 308 ms, 첫 줄까지 98 ms(배포판이 떠 있을 때) | 2026-10-22 실측 (3.1) |
| PowerShell 5.1 의 파이프라인으로 네이티브 명령에 문자열을 넘기면 기본 `$OutputEncoding`(us-ascii)이 한국어를 `?` 로 바꾸고, UTF-8 로 바꿔도 줄 끝에 `\r` 이 붙는다 | 2026-10-22 실측 (3.1) |
| 윈도우 쪽에서 `wsl.exe` 를 강제 종료하거나 그 부모 JVM 이 죽어도 WSL 프로세스에는 신호가 가지 않는다. stdout 에 쓸 때 `SIGPIPE` 를 받을 뿐이다 | 2026-10-22 실측 (3.2 A·B) |
| 부모 JVM 이 죽거나 정상으로 끝나도 자식 `wsl.exe` 는 남는다(그 리눅스 쪽 명령이 끝날 때까지) | 2026-10-22 실측 (3.2 A, 3.5 S2·세 번째 판) |
| `wsl.exe` 의 명령이 정상으로 끝나면 그 명령의 세션에 남은 프로세스가 `SIGHUP` 을 받는다 | 2026-10-22 실측 (3.2 I2·J2) |
| `setsid -f` 로 떼어도 stdio 를 `wsl.exe` 에 물린 채면 `wsl.exe` 의 명령이 곧바로 끝날 때 10 번 중 3 번 죽는다. stdio 를 `/dev/null` 로 돌리거나 `systemd-run --user` 로 띄우면 10/10 산다 | 2026-10-22 실측 (3.2) |
| `wslpath -u` 는 `\\wsl.localhost\…` 와 `\\wsl$\…` 를 둘 다 풀고, 배포판 이름의 대소문자를 가리지 않으며, 다른 배포판의 경로는 풀지 못한다 | 2026-10-22 실측 (3.3) |
| 윈도우 JVM 은 `\\wsl.localhost\<배포판>\…` 를 디렉토리로 읽고, 정규화해도 `\\wsl$` 와 `\\wsl.localhost` 를 하나로 맞추지 않는다. WSL 의 절대 경로 심볼릭 링크는 따라가지 못한다 | 2026-10-22 실측 (3.3) |
| `wsl.exe -l -v` 의 기본 출력은 BOM 없는 UTF-16LE 이고, 환경 변수 `WSL_UTF8=1` 이면 `\r\n` 줄 끝의 UTF-8 이다. `-l -q` 에는 기본 배포판 표시가 없다 | 2026-10-22 실측 (3.4) |
| WSL 안의 Unix 소켓에서 기다리는 프로세스에 윈도우 JVM 이 `wsl.exe -e nc -U <소켓>` 의 stdio 로 붙고, 끊고, 다시 붙을 수 있다 | 2026-10-22 실측 (3.5) |
| OpenBSD `nc -U` 는 `-N` 없이 stdin EOF 를 소켓으로 넘기지 않고 끝나지도 않는다. 부모 JVM 이 죽으면 중계와 `wsl.exe` 가 5 분 넘게 남았다. `-N` 을 주면 1 초 안에 넘기고 끝난다 | 2026-10-22 실측 (3.5) |
| `wsl.exe -e` 로 뜬 프로세스에 `XDG_RUNTIME_DIR=/run/user/1000/` 이 있다(`systemd=true`) | 2026-10-22 실측 (3.5) |
| `wsl.exe -e` 로 뜬 프로세스의 PATH 에 `~/.local/bin` 이 없다. `bash -lc` 는 넣고 `zsh -lc` 는 이 사용자 설정에서 넣지 않는다 | 2026-10-22 실측 (3.7) |
| 윈도우 JVM 이 `wsl.exe -e` 에 넘긴 인자에서 큰따옴표가 사라진다. 공백·한국어·역슬래시·`$`·`;` 는 그대로다 | 2026-10-22 실측 (3.7) |
| `wsl.exe -d <배포판> --cd ~ -e .local/bin/kyu version` 이 셸 없이 사용자 홈 기준으로 실행된다 | 2026-10-22 실측 (3.7) |
| Compose·Skiko 를 뺀 `kotlin-stdlib`·`kotlinx-coroutines`·`kotlinx-serialization-json` 만의 JVM(OpenJDK 21)은 첫 줄까지 64 ~ 85 ms, RSS 53.7 ~ 59.5 MB | 2026-10-22 실측 (3.6) |
| `desktop` 의 `runtimeClasspath` 55 개 jar 중 화면 전용이 33.7 MB, 나머지 5.5 MB | 2026-10-22 실측 (3.6) |
| Compose 플러그인 1.12.0 에 `downloadWix`·`unzipWix` 태스크와 `wix311`·`WIX_PATH` 문자열이 있다 | 플러그인 jar 의 `WixToolsetKt.class` 문자열 (실측 아님) |
| 앱 소켓은 `kind` 가 없는 줄을 승인 물음으로 읽는다 | `desktop/src/main/kotlin/com/kyuchestration/desktop/terminal/chat/AppSocket.kt:345` |
| 챗 홀더는 화면에 보이는 세션의 대화만 `state` 로 내놓는다 | `ChatSessionStateHolder.kt:1331`(`updateConversation`) |
| 관문 물음은 객체 정체성으로 찾는다 | `ChatSessionStateHolder.kt:1400`(데이터 클래스가 아님) · `gateDecisions` |
| 엔진의 비밀 저장소는 키체인 → secret-service → 설정 파일 순으로 고른다 | `internal/secretstore/vault.go:39` |

---

## 부록 B. 실측 재현 방법

**스크래치에서만 돈다.** 레포 안에서 돌리지 않는다. 이번 실측의 스크립트와 기록은 세션 스크래치 디렉토리 `…/scratchpad/core-ui-split/`(이 세션이 끝나면 사라진다)에 있었다. 아래는 다시 만들 수 있게 모양을 적는다.

**재료**

| 파일 | 하는 일 |
|---|---|
| `WinHarness.java` | **윈도우 JVM 으로 돈다**(WSL 의 `javac --release 21` 로 컴파일, `java.exe -cp \\wsl.localhost\<배포판>\…\classes WinHarness …`). 갈래 다섯 — `echo`(3.1), `spawn`·`spawnraw`(3.2·3.7: `wsl.exe` 를 띄우고 stdin 지시 `halt`·`kill`·`close`·`wait`·`status`·`quit` 를 받는다), `relay`(3.5: `wsl.exe -e <KYU_RELAY> <소켓>`, 중계 명령은 환경 변수 `KYU_RELAY` — WSL 에서 넘길 때는 `WSLENV=KYU_RELAY`), `paths`(3.3) |
| `echo_core.py` | 3.1 의 WSL 쪽. 인사 줄(한국어·`PATH`·`XDG_RUNTIME_DIR` 등)을 보내고, 받은 줄마다 `recvLen`·`endsWithCR`·`utf8`·`b64`(받은 바이트) 를 줄마다 flush 해 돌려준다. EOF 에 `{"kind":"eof"}` |
| `life.sh` | 3.2 의 WSL 쪽. 시작할 때 pid·ppid·sid·pgid·stdio 를, 그 뒤 매초 `alive` 를 기록하고, `HUP`·`TERM`·`PIPE`·`INT` 를 받으면 적는다 |
| `socket_core.py` | 3.5 의 WSL 쪽. `$XDG_RUNTIME_DIR/kyu-core-probe.sock` 에서 연결마다 스레드 하나로 받고, 붙으면 스냅샷(`commandsSeen`·`connections`), 명령마다 `ack`, `shutdown` 에 끝난다. 첫 판은 연결을 하나씩만 받았다 |
| `printargs.py` | 3.7. 받은 argv 와 `shutil.which("claude")`·`which("kyu")` |
| `HeadlessCoreBaseline.java` | 3.6. `Dispatchers.getIO()`·`Json.Default` 를 건드리고 stdin 의 JSON 줄을 파싱해 답한다. 클래스패스는 Gradle 캐시의 `kotlin-stdlib-2.4.10`·`kotlinx-coroutines-core-jvm-1.11.0`·`kotlinx-serialization-{core,json}-jvm-1.11.0` |
| `classpath-sizes.init.gradle.kts` | 3.6. init 스크립트로 `printClasspathSizes` 태스크를 얹어 `runtimeClasspath` 를 그룹으로 갈라 센다. `cd desktop && ./gradlew -q --offline --init-script <파일> printClasspathSizes` |

**절차**

| 절 | 스크립트 | 단계 |
|---|---|---|
| 3.1 | `m1-stdio.sh` | (가) `WinHarness echo` — 인사 → 샘플 넷 → 작은 줄 200 왕복 → 1 MiB·8 MiB 목표의 큰 줄 → stdin 닫기. (나) `powershell.exe -NoProfile -Command "$l='{…}'; $l \| wsl.exe -d D -e python3 echo_core.py"` 를 기본 인코딩으로 한 번, `$OutputEncoding`·`[Console]::OutputEncoding` 을 UTF-8 로 바꿔 한 번 |
| 3.2 | `m2-lifetime.sh` · `m2b-lifetime.sh` · `m2c-lifetime.sh` · `m2d-race.sh` | 시나리오마다 `WinHarness spawn` 에 지시를 시각에 맞춰 넣고, 4 초·7 초 뒤 `tasklist.exe /FI "PID eq <wsl.exe>"` · `pgrep -af "life.sh <태그>"` · 기록 꼬리를 본다. `m2d` 는 같은 모양을 10 번씩 돌려 1.5 초 뒤 산 것을 센다 |
| 3.3 | `m3-paths.sh` | 한국어·공백 이름 폴더 아래 `Repo`·`repo`·`target` 과 절대 심볼릭 링크를 만들고, `wslpath -w`/`-u` 일곱 가지, `WinHarness paths` 를 두 형식으로, PowerShell 의 `[System.IO.Path]::GetFullPath`·`Directory.Exists`·`Resolve-Path`·`Get-ChildItem`·`Get-Item .Target` |
| 3.4 | `m4-distros.sh` | `wsl.exe -l -v` 원바이트(`xxd`)와 `iconv -f UTF-16LE`, `WSL_UTF8=1 WSLENV=WSL_UTF8` 로 같은 명령, `-l -q`, `--version`, 레지스트리의 `Lxss\DefaultDistribution` |
| 3.5 | `m5-relay.sh` | 코어를 `wsl.exe -e bash -c "setsid -f python3 socket_core.py … </dev/null >/dev/null 2>&1; <소켓이 설 때까지 기다림>"` 로 띄우고, `WinHarness relay` 를 `nc -U` 로 셋(kill·halt·close), `nc -N -U` 로 셋(halt·close·정상 종료), 마지막에 `shutdown` |
| 3.6 | `m6-jvm.sh` | fifo 로 stdin 을 열어 둔 채 `HeadlessCoreBaseline` 을 띄우고 첫 줄까지의 시간, 200 줄 뒤 3 초의 `ps -o rss=`. 플래그 넷 |
| 3.7 | `m7-path.sh` · `m7b-argv.sh` · `m7c-launch.sh` | `printargs.py` 를 셸 없이·`sh -c`·`bash -lc`·`zsh -lc`·`zsh -ic` 로. 인자 여섯 가지. `spawnraw` 로 `--cd ~ -e pwd` 와 `--cd ~ -e .local/bin/kyu version` |

**뒷정리**: 3.2 의 시나리오는 프로세스를 일부러 남긴다. 끝나면 `pkill -f life.sh`, `systemctl --user stop kyu-probe-F.service`. 3.5 는 `shutdown` 명령이 코어를 끝내고 남은 `nc` 도 함께 끝난다.

---

## 부록 C. 용어

| 낱말 | 뜻 |
|---|---|
| **코어** | 세션을 소유하는 헤드리스 JVM(`kyuchestration-core`). 상태 홀더·세션 트리·앱 소켓·`claude` 프로세스를 쥔다. 원칙 5·16 의 "앱" |
| **화면** | 코어의 상태를 그리고 명령을 보내는 Compose 프로그램. 윈도우에서는 msi 로 깔리는 네이티브 창 |
| **틀(frame)** | 화면 ↔ 코어 사이 JSON 한 줄. `hello`·`welcome`·`snapshot`·`change`·`command`·`reply`·`bye` |
| **스냅샷** | 화면이 붙을 때 코어가 보내는 지금 상태 전부. 여러 틀로 나뉘어 온다 |
| **변화** | 스냅샷 뒤 상태가 바뀔 때마다 오는 틀. 작은 상태는 통째로, 대화는 머리·꼬리·흐르는 글로 |
| **게시자** | 코어 안에서 홀더의 `StateFlow` 를 구독해 변화로 바꾸는 자리(`ScreenChangePublisher`) |
| **거울** | 화면 안에서 변화를 받아 같은 모양의 `StateFlow` 를 다시 세우는 자리(`CoreMirror`) |
| **`serve`** | 코어의 동사. 세션을 쥐고 화면을 기다린다. 단계 2~4 는 `serve --stdio`(화면 하나, stdin EOF 에 끝), 5 단계부터 데몬 |
| **`attach`** | 코어의 동사. 화면의 stdio 를 데몬의 `core.sock` 에 잇는 중계. 데몬이 없으면 띄운다 |
| **화면 경로 · 코어 경로** | 화면 OS 의 경로(`\\wsl.localhost\Ubuntu-24.04\home\…`, `C:\…`)와 코어 OS 의 경로(`/home/…`). 선 위에는 코어 경로만 다닌다(6.9) |
| **떼기** | 화면이 코어와의 연결만 놓는 것. 세션은 남는다. 끝내기(`shutdown_core`)와 다르다 |
