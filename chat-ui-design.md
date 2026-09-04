# 앱 자체의 챗 — `claude` 를 프로그램으로 부린다

> 지금 앱은 `claude` 가 그린 터미널 화면을 그대로 띄운다. 이 설계는 그것을 그만두고 **앱이 `claude` 의 구조화된 이벤트를 받아 자기 화면을 직접 그리는 것**으로 바꾼다. 그러면 도구 호출이 카드가 되고, 권한 요청이 버튼이 되고, 비용이 배지가 된다 — 터미널 안에서는 할 수 없던 것들이다.

- 작성일: 2026-09-04
- 상태: **설계 확정 전 — 실측 완료, 사용자 승인 대기.** 구현 미착수
- 선행 문서: [workdir-orchestrator-design.md](workdir-orchestrator-design.md) — 특히 1.3(설정은 세션을 시작한 디렉토리에서만 온다), 원칙 3·5
- 선행 문서: [app-owned-sessions-design.md](app-owned-sessions-design.md) — 특히 5.2(`kyu session-command`), 5.3(앱이 세션을 여는 길), 5.5(대화 이어가기), 원칙 11·12
- 선행 문서: [orchestration-tools-design.md](orchestration-tools-design.md) — 특히 5.2(`--mcp-config` 등록), 5.4(위임 실행), 원칙 13
- 전제: **앱이 `claude` 를 직접 보유한다.** 그 전환은 PR #42 로 끝났고(app-owned 7.0), 이 설계는 "보유한 프로세스와 무엇을 주고받는가" 만 바꾼다. 엔진(`kyu`)은 여전히 순수 엔진이고 세션을 띄우지 않는다
- **이 문서는 선행 문서가 기각한 안을 다시 꺼낸다.** app-owned 3.4 가 *"앱이 `claude` 를 기계 프로토콜로 몰고 화면을 직접 그린다"* 를 세 사유로 기각했다. 사용자의 요구가 정확히 그 안이므로, 2 절이 그 세 사유를 하나씩 다시 본다 — **둘은 아직 유효하고 하나는 뒤집힌다.** 그 판단이 이 문서의 뼈대다
- 이 문서가 열지 않는 것: 색상 팔레트의 세부. 사용자에게 별도 시각 목업이 가는 중이고, 6 절은 **구조·토큰 자리·컴포넌트 목록**까지만 정한다

---

## 1. 배경 — 왜 화면 모델을 바꾸는가

### 1.1 사용자가 말한 것

2026-09-04, 두 문장이다.

> "좀 모던한 디자인이면 좋겠어 — 머테리얼 디자인?"

> "지금은 클로드 세션을 화면에 바로 띄우는데, 이게 아니라 **컨덕터처럼 이 프로그램 자체의 챗**으로 동작하면 좋겠어."

둘이 따로 보이지만 하나다. **터미널을 띄우고 있는 한 디자인을 바꿀 수 있는 면적이 창틀뿐이다.** 창 안의 대부분은 `claude` 가 ANSI 로 그린 격자이고, 앱이 거기에 할 수 있는 일은 폰트와 배경색을 정하는 것까지다. 머티리얼 디자인을 적용할 자리 자체가 없다.

그래서 이 문서는 두 요구를 한 걸음으로 다룬다. **화면 모델을 바꾸는 것이 곧 디자인을 열 수 있게 하는 것이다.**

### 1.2 지금 구조 — `claude` 가 그린 것을 그대로 보인다

앱은 PTY 를 열고 그 안에서 `claude` 를 띄운 뒤, JediTerm 위젯으로 그 PTY 를 화면에 그린다.

| 자리 | 파일 |
|---|---|
| 무엇을 띄울지 엔진에게 묻는다 | `desktop/src/main/kotlin/com/kyuchestration/desktop/terminal/SessionCommandSource.kt` |
| PTY 를 열고 `claude` 를 띄운다 | `desktop/src/main/kotlin/com/kyuchestration/desktop/terminal/SessionTerminalOpener.kt` |
| 보유 중인 세션을 쥔다 | `desktop/src/main/kotlin/com/kyuchestration/desktop/terminal/EmbeddedTerminalStateHolder.kt` |
| 화면에 격자를 그린다 | `desktop/src/main/kotlin/com/kyuchestration/desktop/EmbeddedTerminalPane.kt` |
| 보유 세션마다 위젯을 유지한다 | `desktop/src/main/kotlin/com/kyuchestration/desktop/HeldSessionTerminalWidgets.kt` |

이 구조의 미덕은 분명하다. **`claude` 가 할 줄 아는 모든 것이 공짜로 화면에 있다.** 슬래시 명령, 권한 프롬프트, 플랜 모드, `/login` 흐름 — 앱이 아무것도 몰라도 다 된다. app-owned 3.4 가 기계 프로토콜을 기각한 이유가 정확히 그것이었다.

### 1.3 그 미덕의 대가

대가는 **앱이 세션 안에서 무슨 일이 벌어지는지 하나도 모른다는 것**이다. 앱이 아는 것은 "PTY 가 살아 있다" 와 "종료 코드가 몇이었다" 뿐이다. `HeldSession` 이 쥐고 있는 것도 `TtyConnector` 하나다(`desktop/src/main/kotlin/com/kyuchestration/desktop/terminal/HeldSession.kt:17`).

그래서 지금 못 하는 것이 줄줄이 따라온다.

| 못 하는 것 | 왜 |
|---|---|
| 도구 호출을 카드로 보이기 | 무엇이 불렸는지 앱이 모른다 — 격자 안의 글자일 뿐이다 |
| 권한 요청에 앱의 버튼으로 답하기 | 프롬프트가 터미널 안에서 뜨고 터미널 안에서 답한다 |
| 이번 턴의 비용·토큰 보이기 | 숫자가 오지 않는다 |
| 대화 검색·스크롤백 | 격자에는 "메시지" 라는 단위가 없다 |
| 위임 진행 표시 | 오케스트레이션 문서 6.2 가 이것을 열린 질문으로 남겼다 |
| 창 폭을 자유롭게 쓰기 | 세션 화면이 80 칸 기준이라 좌우 분할이 안 된다 — `KyuchestrationDesktopScreen.kt:157` 의 주석이 위아래 분할의 근거로 그것을 적어두었다 |
| 머티리얼 디자인 | 1.1 |

**마지막 둘이 사용자의 두 문장에 그대로 대응한다.**

### 1.4 지금 테마는 기본값 그대로다

앱은 `MaterialTheme { }` 를 인자 없이 부른다 — `desktop/src/main/kotlin/com/kyuchestration/desktop/KyuchestrationDesktopScreen.kt:91`.

인자가 없다는 것은 색 스킴도, 타이포그래피도, 셰이프도 Material 3 의 baseline 기본값이라는 뜻이다. 다크 모드도 없다. 코드베이스 전체에 `darkColorScheme` · `lightColorScheme` 이 한 번도 나오지 않는다.

**"모던한 디자인" 을 하려면 먼저 이 한 줄에 넣을 것이 있어야 하고, 넣은 것이 보일 면적이 있어야 한다.** 면적을 만드는 것이 1.2~1.3 이고, 넣을 것을 정하는 것이 6 절이다.

### 1.5 챗이 여는 것

화면 모델을 바꾸면 위 표가 뒤집힌다.

| 열리는 것 | 근거 |
|---|---|
| 도구 호출 카드 — Bash 명령, Edit 의 diff, Read 의 파일 | 3.4 — `tool_use` 블록과 `tool_use_result` 곁가지가 그대로 온다 |
| 인라인 권한 승인 — 허용·거절·인자 수정 | 3.5 · 3.6 |
| 비용·토큰·한도 배지 | 3.12 |
| 위임 진행 | 3.10 |
| 대화가 메시지 목록이 된다 — 검색·접기·복사 | 3.1 |
| 좌우 3 분할 셸 | 챗은 폭에 맞춰 흐른다 |
| 머티리얼 디자인 | 위 전부가 Compose 컴포넌트가 되므로 |

---

## 2. 핵심 문제 — 기각된 안을 다시 꺼낸다

app-owned 3.4 가 이 안을 통째로 기각했다. 그 문서의 제목이 이 문서가 하려는 일과 글자 그대로 같다 — *"앱이 `claude` 를 기계 프로토콜로 몰고 화면을 직접 그린다"*.

**그러므로 이 문서의 첫 일은 그 기각을 뒤집는 것이 아니라 그 사유를 다시 읽는 것이다.** 사유가 셋이었고, 셋을 하나씩 본다.

### 2.1 기각 사유 (1) — 원칙 3 위반

> *"선행 문서 원칙 3('세션 내부에 개입하지 않는다') 정면 위반이다. 도구가 세션의 입출력을 해석하기 시작하면 세션이 무엇을 하는지가 도구의 관심사가 된다."*

**이 사유는 유효하다. 뒤집지 않고, 원칙 3 의 뜻을 좁혀 읽는다.**

원칙 3 이 막으려던 것은 workdir 문서에서 분명하다 — *"도구는 세션을 띄우고, 보여주고, 진입시킬 뿐 세션의 입출력을 **가로채지** 않는다."* 가로챈다는 것은 **끼어들어 바꾼다**는 것이다. 그때의 그림은 tmux 세션에 `send-keys` 로 문자를 주입하고 화면을 긁는 도구였다. 그 도구는 세션이 무엇을 하는지 알아야 무엇을 주입할지 정할 수 있다.

챗 어댑터는 다르다. **사용자가 친 것을 그대로 전달하고, `claude` 가 내보낸 것을 그대로 그린다.** 문장을 지어내지도, 도구 호출을 대신 승인하지도, 모델이 못 보게 무언가를 걸러내지도 않는다 — 그 셋 중 하나라도 하는 순간 이 설계는 원칙 3 을 진짜로 어긴다. 그것이 원칙 14("전사는 한 물줄기에서만 온다")와 원칙 15("못 그리는 것을 그린 척하지 않는다")를 4 절에 두는 이유다. **두 원칙이 이 사유에 대한 답이고, 동시에 이 설계가 넘지 않기로 한 선이다.**

그리고 사유 (1)이 겨눈 다른 절반 — *"세션이 무엇을 하는지가 도구의 관심사가 된다"* — 은 이미 벌어졌다. orchestration 설계가 `run_in_repo` 로 위임의 결과를 읽고 원출력을 `.coord/runs/` 에 적기로 했을 때(orchestration 5.4.2·5.4.3), 도구는 이미 세션이 무엇을 했는지를 관심사로 삼았다. **이 문서가 여는 것이 아니라 이미 열린 문이다.**

### 2.2 기각 사유 (2) — `claude` 를 따라잡는 경주

> *"`claude` 의 대화형 UI 를 앱이 다시 만들게 된다 — 권한 확인 프롬프트, 계획 모드, 슬래시 명령, 파일 선택기, 진행 표시. 그리고 `claude` 가 그것을 고칠 때마다 앱이 따라가야 한다."*

**이 사유가 가장 무겁고, 실측이 절반을 덜어냈다.**

| (2)가 든 것 | 3 절이 잰 것 |
|---|---|
| 슬래시 명령 | **다시 만들 것이 없다.** 텍스트를 그대로 보내면 돈다. 목록은 `init` 이 준다(3.13) |
| 권한 확인 프롬프트 | 앱이 화면만 그린다. 판정은 여전히 `claude` 가 하고, 앱은 그 물음을 받아 답을 돌려줄 뿐이다(3.5) |
| 진행 표시 | 서브에이전트는 이벤트로 온다(3.10). MCP 도구는 안 온다 — **그래서 그리지 않는다**(원칙 15) |
| 계획 모드 | **못 쟀다.** 10 절 1 번이고, 7 절이 이것 때문에 터미널을 한 단계 더 남긴다 |
| 파일 선택기 | v1 밖(6.5) |

**경주가 사라지지는 않는다. 트랙이 짧아진 것이다.** 그리고 짧아진 트랙의 길이를 이 문서는 안다 — 3 절이 잰 이벤트 목록이 곧 앱이 따라가야 할 표면 전부다. 그 표면이 바뀌면 5.3.2 의 sealed 이벤트 매핑 한 곳이 깨지고, 깨지는 자리가 하나인 것이 (2)에 대한 구조적 답이다.

**그리고 이 사유는 원래 대칭이 아니었다.** 따라잡지 않으면 잃는 것도 있다 — 지금 앱은 `claude` 가 이미 주고 있는 것(비용·토큰·도구 인자·권한 물음)을 **하나도** 쓰지 못한다. (2)는 "따라가는 비용" 만 셌고 "안 따라가서 못 쓰는 것" 은 세지 않았다.

### 2.3 기각 사유 (3) — 이 도구의 가치는 조율이지 대화 UI 가 아니다

> *"이 도구의 가치는 조율이지 대화 UI 가 아니다. README 가 '각 레포에서 세션을 따로 띄워야 그 레포의 설정이 살아난다' 를 이 도구의 존재 이유로 적어둔 그 자리에 대화 UI 는 없다."*

**이 사유가 뒤집힌다. 뒤집는 것은 실측이 아니라 사용자다.**

사용자가 2026-09-04 에 *"이 프로그램 자체의 챗으로 동작하면 좋겠어"* 라고 말했다. 요구가 바뀐 것이 아니라 **요구에 하나가 더해졌다.** 조율은 그대로 이 도구의 존재 이유이고 — 레포마다 세션이 따로 뜨는 것도, `run_in_repo` 도, `.coord/plan.md` 도 전부 남는다 — 그 조율을 **어느 화면에서 하는가**가 바뀐다.

그리고 (3)이 적어둔 존재 이유는 이 전환으로 조금도 손상되지 않는다. 챗 모드에서도 `claude` 는 여전히 레포 cwd 에서 뜨고, `CLAUDE.md`·`.mcp.json`·훅·에이전트가 그대로 로드된다(3.1 이 지문으로 확인했다). **바뀌는 것은 그 세션과 사람 사이에 무엇이 있는가뿐이다.**

### 2.4 그래서 이 설계의 위험은 하나로 모인다

> **터미널을 버리는 순간 잃는 것의 목록이 정확한가.**

2.2 가 그 목록을 절반 만들었고, 못 쟀다고 적은 칸이 남아 있다. 3 절은 그 목록을 마저 만들기 위해 잰다 — 무엇이 오는지가 아니라 **무엇이 오지 않는지**까지. 그리고 7 절이 그 목록을 근거로 터미널을 언제 지울지 정한다.

**목록이 안 차면 터미널을 안 지운다.** 그것이 이 설계가 기각을 뒤집으면서 지키는 안전장치다.

---

## 3. 실측 — 프로그램 계층이 무엇을 주는가

**2026-09-04, `claude` 2.1.259, WSL2(Linux).** 격리된 스크래치 디렉토리에 가짜 레포를 만들어 쟀다. 재료는 전부 가짜다 — 지문 문구를 담은 `CLAUDE.md`, 권한 요청을 파일에 적고 정해진 답을 돌려주는 초소형 stdio MCP 서버, 오래 돌면서 진행을 알리는 두 번째 MCP 서버, 앱 역할로 유닉스 소켓에서 결정을 답하는 프로세스, 측정용 슬래시 명령 하나. 실제 레포도 실제 토큰도 쓰지 않았다. 재현 방법은 부록 B 다.

**판정 방식은 선행 문서와 같다.** 모델의 말을 믿지 않는다. 권한 브리지는 **서버가 남긴 로그와 실제로 만들어졌는지의 파일**로, 중단은 **프로세스의 다음 답**으로, 진행 알림은 **이벤트가 왔는지 안 왔는지**로 갈랐다.

**대부분의 프로브는 `--setting-sources ""` 로 돌렸다.** 사용자 전역 설정에 `defaultMode: auto` 가 있어서(`~/.claude/settings.json`), 그것을 두고 재면 "권한 프롬프트가 안 왔다" 가 이 판의 성질인지 이 머신의 설정인지 갈리지 않는다.

### 3.1 양방향 스트림 — 한 프로세스가 대화를 통째로 산다

`claude -p --input-format stream-json --output-format stream-json --verbose` 로 띄우고, stdin 에 사용자 메시지를 JSON 한 줄로 쓴다.

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"…"}]}}
```

| 잰 것 | 결과 |
|---|---|
| 한 프로세스에서 두 턴 | ✅ 첫 답이 끝난 뒤 두 번째 메시지를 쓰면 그대로 답한다 |
| 대화가 이어지는가 | ✅ 두 번째 턴이 첫 번째 답을 기억했다 |
| `session_id` | **두 턴이 같다** — `84a72c08-…` |
| `CLAUDE.md` | ✅ 로드된다 — 지문 `KYUCHAT-2026` 을 답했다 |
| 턴 끝 신호 | `{"type":"result", …}` 한 줄. 턴마다 정확히 하나 |

**이것이 이 설계의 뼈대다.** 앱은 프로세스 하나를 세션 하나로 쥐고, 사용자가 무언가 칠 때마다 한 줄을 쓰고, `result` 가 올 때까지 이벤트를 모은다. `claude` 를 매번 새로 띄우는 것이 아니다 — 그랬다면 매 턴 3 초의 부팅과 전체 컨텍스트 재전송이 붙는다.

**`system/init` 이 턴마다 다시 온다.** 두 턴짜리 실행에서 `init` 이 두 번 왔고 `session_id` 는 같았다. 어댑터가 `init` 을 "새 세션이 시작됐다" 로 읽으면 매 턴 대화를 비우게 된다 — 세션의 시작은 **프로세스의 시작**이지 `init` 이 아니다.

### 3.2 이벤트 목록 — 앱이 받는 것 전부

프로브 26 회에서 나온 최상위 `type` 은 일곱이다.

| `type` | 언제 | 앱이 쓰는 곳 |
|---|---|---|
| `system` | 세션 초기화·훅·상태·서브에이전트 | 아래 표 |
| `assistant` | 모델이 **콘텐츠 블록 하나를 완성할 때마다** | 말풍선 · 도구 카드 · 사고 블록 |
| `user` | 도구 결과, 중단 표시, 되돌려받은 사용자 메시지 | 도구 카드의 결과 칸 |
| `stream_event` | `--include-partial-messages` 를 켰을 때의 글자 단위 조각 | 스트리밍 표시 |
| `result` | 턴이 끝날 때 | 비용 배지 · 입력창 잠금 해제 |
| `rate_limit_event` | 턴마다 | 사용량 표시 |
| `control_response` | 앱이 보낸 제어 요청의 답 | 중단 확인 |

`system` 의 `subtype` 은 열이다.

| `subtype` | 담는 것 |
|---|---|
| `init` | `session_id` · `cwd` · `model` · `permissionMode` · `tools` · `mcp_servers`(이름과 상태) · `agents` · `slash_commands` · `memory_paths` · `capabilities` |
| `status` | `{"status":"requesting"}` — API 요청을 보내는 중 |
| `thinking_tokens` | `estimated_tokens` · `estimated_tokens_delta` |
| `hook_started` · `hook_response` | 훅 이름·이벤트·종료 코드·출력 |
| `task_started` | 서브에이전트 시작 — `task_id` · `tool_use_id` · `subagent_type` · `spawn_depth` · `prompt` |
| `task_progress` | 서브에이전트 진행 — `usage.total_tokens` · `tool_uses` · `duration_ms` · `last_tool_name` |
| `task_updated` | 상태 변화 — `completed` · `killed` |
| `task_notification` | 끝났음과 `output_file` 경로 |
| `background_tasks_changed` | 백그라운드 작업 목록 전체 |

**`assistant` 는 메시지 단위가 아니라 블록 단위로 온다.** 한 턴에서 텍스트 블록 하나와 도구 호출 블록 하나가 나오면 `assistant` 이벤트가 둘이다. 어댑터가 "assistant 이벤트 하나 = 말풍선 하나" 로 두면 도구 호출이 빈 말풍선이 된다.

### 3.3 부분 스트리밍 — 글자 단위로 온다

`--include-partial-messages` 를 켜면 `stream_event` 가 붙는다. 안쪽 `event.type` 은 여섯이다.

| `event.type` | 담는 것 |
|---|---|
| `message_start` | 모델 이름 · 메시지 id · 첫 `usage` · `ttft_ms` |
| `content_block_start` | 이 블록이 무엇인지 — `{"type":"text"}` 또는 `{"type":"tool_use","id":…,"name":"Read","input":{}}` |
| `content_block_delta` | `text_delta` 의 글자 조각, 또는 `input_json_delta` 의 인자 JSON 조각 |
| `content_block_stop` | 블록 끝 |
| `message_delta` · `message_stop` | 메시지 끝 |

**도구 이름은 인자보다 먼저 온다.** `content_block_start` 에 `name: "Read"` 가 실려 있고 `input` 은 비어 있다 — 인자는 `input_json_delta` 로 조금씩 이어 붙는다. 그래서 챗 UI 는 "Read 도구를 부르는 중" 을 인자가 다 오기 전에 그릴 수 있다.

**대가**: `input_json_delta` 는 잘린 JSON 조각이라 그 자체로 파싱되지 않는다. 완성된 인자는 블록이 끝난 뒤 `assistant` 이벤트에 온전한 객체로 다시 온다. **어댑터는 조각을 이어 붙여 파싱하려 들지 않고, 완성본을 기다린다.**

### 3.4 도구 호출과 결과 — 카드를 그릴 재료가 다 있다

`assistant` 의 도구 호출 블록:

```json
{"type":"tool_use","id":"toolu_01R5ZERot1B5tdJ6XAngsu7V","name":"Read",
 "input":{"file_path":"…/README.txt"},"caller":{"type":"direct"}}
```

`user` 의 결과 이벤트는 **두 겹**이다.

```json
{"type":"user",
 "message":{"role":"user","content":[
   {"tool_use_id":"toolu_01R5ZERot1B5tdJ6XAngsu7V","type":"tool_result",
    "content":"1\thello from fake repo\n2\t"}]},
 "tool_use_result":{"type":"text","file":{"filePath":"…/README.txt",
   "content":"hello from fake repo\n","numLines":2,"startLine":1,"totalLines":2}}}
```

**`tool_use_result` 가 이 설계에 중요하다.** `content` 는 모델이 읽는 텍스트라 줄 번호가 붙어 있고, `tool_use_result` 는 그 도구가 실제로 한 일을 도구마다 다른 모양으로 담는다. Read 는 파일 메타를 주고, Bash 는 표준출력을, 거절된 호출은 `"Error: …"` 문자열을 준다. **도구별 카드를 그리는 근거가 이 곁가지다.**

`tool_use_id` 로 호출과 결과가 이어진다. 카드 하나를 그리는 데 필요한 것이 전부 있다.

### 3.5 권한 브리지 — MCP 도구 하나로 온다

**이것이 이 설계에서 가장 큰 미지수였고, 답이 깨끗하다.**

`--permission-prompt-tool mcp__<서버>__<도구>` 를 주면, 승인이 필요한 도구 호출마다 그 MCP 도구가 불린다. 프로브 서버가 받은 것 그대로다.

```json
{"method":"tools/call","id":2,
 "params":{"name":"approve",
  "arguments":{"tool_name":"Write",
   "input":{"file_path":"…/probe-written.txt","content":"WRITTEN\n"},
   "tool_use_id":"toolu_01EK4pNYexJLbkuiiosevhXq"},
  "_meta":{"claudecode/toolUseId":"toolu_01EK4pNYexJLbkuiiosevhXq","progressToken":2}}}
```

답은 MCP 도구 결과의 텍스트 안에 JSON 으로 넣는다. 세 갈래를 다 쟀다.

| 답 | 결과 |
|---|---|
| `{"behavior":"allow","updatedInput":{…원본…}}` | 도구가 그대로 실행된다 |
| `{"behavior":"allow","updatedInput":{…바꾼 것…}}` | **바꾼 인자로 실행된다** — 모델은 `echo ORIGINAL > upd.txt` 를 요청했는데 결과가 `MODIFIED-BY-APP` 이었고, `upd.txt` 는 만들어지지 않았다 |
| `{"behavior":"deny","message":"…"}` | `tool_result` 가 `is_error: true` 와 그 문구로 온다. **파일은 만들어지지 않았다** |

**모델은 이 도구를 볼 수 없다.** `--permission-prompt-tool` 로 지정된 도구는 `init` 의 `tools` 목록에서 사라진다. 같은 서버를 지정 없이 붙였을 때는 `mcp__kyuprobe__approve` 가 목록에 있었고, 지정하자 사라졌다. **모델이 자기 승인 도구를 스스로 부르는 길이 애초에 없다.**

| 실행 | `--permission-prompt-tool` | 모델에게 보인 도구 |
|---|---|---|
| 두 서버, 지정 없음 | — | `mcp__kyuprobe__approve` · `mcp__kyuslow__run_in_repo_probe` |
| 두 서버, `approve` 지정 | ✅ | `mcp__kyuslow__run_in_repo_probe` 만 |

### 3.6 권한 모드별 — 무엇이 관문을 타는가

같은 요청을 `--permission-mode` 만 바꿔 여섯 번 돌렸다. `--setting-sources ""` 로 사용자 설정을 전부 걷어낸 상태다.

| `--permission-mode` | `init` 이 보고한 값 | 시킨 일 | 승인 도구가 불렸나 | 결과 |
|---|---|---|---|---|
| `manual` | `default` | `Write` | ✅ 1 회 | 거절하면 파일이 안 생긴다 |
| `manual` | `default` | `Bash("echo …")` | ❌ 0 회 | 그냥 실행된다 |
| `acceptEdits` | `acceptEdits` | `Write` | ❌ 0 회 | 그냥 실행된다 |
| `auto` | `auto` | `Write` | ❌ 0 회 | 그냥 실행된다 |
| `bypassPermissions` | `bypassPermissions` | `Write` | ❌ 0 회 | 그냥 실행된다 |
| `dontAsk` | `dontAsk` | `Write` | ❌ 0 회 | **자동 거절**된다. `result.permission_denials` 에 항목이 남는다 |

셋을 읽어야 한다.

1. **`manual` 이 `claude` 안에서는 `default` 다.** 플래그 이름과 `init` 이 보고하는 이름이 다르다 — 앱이 `init.permissionMode` 를 화면에 그대로 보이면 사용자가 준 적 없는 낱말이 뜬다.
2. **모든 도구 호출이 관문을 타지는 않는다.** `default` 에서도 `echo` 는 그냥 돈다. `claude` 가 자체 분류로 무해한 것을 먼저 거른다. **챗 UI 의 승인 카드는 "도구를 부를 때마다" 가 아니라 "관문이 열릴 때만" 뜬다.**
3. **`dontAsk` 는 브리지를 무력화한다.** 묻지 않고 거절하고, 그 사실은 `result.permission_denials` 에만 남는다. 챗 UI 는 이 배열을 읽어 "권한이 없어 못 한 일" 을 보여야 한다 — 안 그러면 모델이 "권한이 없어서 못 했습니다" 라고 말하는 것이 유일한 통로가 된다.

### 3.7 서버가 앱에게 묻는다 — 유닉스 소켓 왕복

**여기가 이 설계의 구조적 난점이다.** 승인 도구를 제공하는 MCP 서버는 `claude` 의 **자식**이고, 결정을 내리는 사람은 **앱(부모)** 앞에 있다. 서버가 부모에게 물을 통로가 있어야 한다.

프로브 서버에 유닉스 소켓 왕복을 넣고, 앱 역할 프로세스가 일부러 늦게 답하게 했다.

| 앱이 답하기까지 | 결과 |
|---|---|
| 12 초 | ✅ 통과. 전체 실행 20.2 초, 파일에 `VIA-SOCKET` 이 쓰였다 |
| **150 초** | ✅ 통과. 전체 실행 158.6 초, 파일에 `SLOW-APPROVAL` 이 쓰였다. 서버가 잰 왕복은 149,986 ms |

**사람이 2 분 반을 고민해도 `claude` 는 기다린다.** 승인 카드를 띄워두고 사용자가 커피를 마시러 가는 시나리오가 성립한다는 뜻이다. 상한은 못 찾았고, 이 값이면 v1 에 충분하다.

**함정 하나를 실측에서 만났다.** `AF_UNIX` 경로는 107 바이트가 한계다. 스크래치 경로를 그대로 쓰자 `OSError: AF_UNIX path too long` 으로 소켓이 열리지 않았다. **소켓은 워크디렉토리 아래가 아니라 짧은 런타임 디렉토리에 두어야 한다** — 프로브는 `$XDG_RUNTIME_DIR` 에 두고 통과했다. 워크디렉토리 경로는 사용자가 정하므로 얼마든지 길어질 수 있고, 그 길이가 승인 기능 전체를 조용히 죽인다.

### 3.8 중단 — 대화를 죽이지 않는다

`init` 의 `capabilities` 가 `["interrupt_receipt_v1","interrupt_cancel_queued_v1","msg_lifecycle_v1"]` 라고 스스로 말한다. 그대로 보냈다.

```json
{"type":"control_request","request_id":"int_b8acf0f7","request":{"subtype":"interrupt"}}
```

돌아온 것:

```json
{"type":"control_response","response":{"subtype":"success",
 "request_id":"int_b8acf0f7","response":{"still_queued":[]}}}
```

그리고 이어서 `user` 이벤트에 `[Request interrupted by user]`, 그다음 `result` 가 `subtype: "error_during_execution"` · `terminal_reason: "aborted_streaming"` · `result: null` 로 온다. 도구가 백그라운드로 돌고 있었으면 `system/task_updated` 가 `status: "killed"` 로 그것을 알린다.

**결정적인 것은 그다음이다.** 긴 글을 쓰던 중에 중단하고, **같은 프로세스에** 다음 메시지를 밀어 넣었다.

| 걸음 | 결과 |
|---|---|
| 중단 | `aborted_streaming` |
| 0.5 초 뒤 "방금 무슨 주제를 쓰고 있었지?" | **"헥사고날"** — 답했다 |

**프로세스도 대화도 살아 있다.** 중단은 이번 턴만 끊는다. 이것이 없었으면 챗 UI 의 중단 버튼은 세션을 끝내는 버튼이 됐을 것이다.

**`SIGINT` 은 다르다.** 같은 조건에서 시그널을 보내면 턴은 똑같이 `aborted_streaming` 으로 끝나지만 **프로세스가 함께 끝난다**(종료 코드 0). 앱은 `SIGINT` 을 쓰지 않는다.

### 3.9 `resume` · `--session-id` — 이 모드에서도 산다

| 잰 것 | 결과 |
|---|---|
| `--session-id <UUID>` 로 새 대화를 열고 암호를 알려준다 | ✅ |
| **새 프로세스**를 `--resume <같은 UUID>` 로 띄우고 암호를 묻는다 | ✅ `CHATRESUME-88` |
| 없는 UUID 로 `--resume` | 종료 코드 1. stderr 에 `No conversation found with session ID: …`. **그 전에 `result` 이벤트가 `error_during_execution` 으로 한 줄 온다** |

**실패가 지금보다 훨씬 잘 보인다.** PTY 시절에는 앱이 보는 것이 곧바로 닫힌 PTY 뿐이라 사용자의 `/exit` 과 구분할 수 없었고, 그래서 `session-command` 의 답에 `resumedConversationId` 를 실었다(app-owned 5.5.4). 챗 모드에서는 stderr 한 줄과 `result` 이벤트가 이유를 말한다.

**그렇다고 그 필드를 걷어내지 않는다.** 이유가 둘이다 — 터미널 경로가 한 단계 동안 남고(7 절), 그 필드는 `--resume` 이 실패하기 **전에** 앱이 이미 알고 있어야 하는 사실이라(어느 대화를 이어가려 했는지) stderr 를 기다릴 이유가 없다.

### 3.10 위임 진행 — MCP 알림은 안 오고, 서브에이전트는 온다

`run_in_repo` 같은 오래 도는 MCP 도구의 진행을 챗에 보일 수 있는가. **두 길을 다 쟀고 답이 갈린다.**

**(가) MCP 진행 알림 — 오지 않는다.** 프로브 서버가 `_meta.progressToken` 을 받아 `notifications/progress` 를 3 회 보냈다. 서버 로그에 보낸 기록이 남아 있다. 그런데 `claude` 의 출력 스트림에서 `progress` 라는 낱말이 들어간 이벤트는 **0 개**였다. `tool_use` 와 `tool_result` 사이 6 초 동안 아무것도 오지 않았다.

**(나) 서브에이전트(`Task`) — 온다.** 그것도 풍부하게.

| 이벤트 | 담는 것 |
|---|---|
| `system/task_started` | `task_id` · `tool_use_id` · `subagent_type` · `spawn_depth` · 프롬프트 전문 |
| `system/task_progress` | `description`("Reading README.txt") · `usage.total_tokens` · `tool_uses` · `duration_ms` · `last_tool_name` |
| `system/task_updated` | `{"status":"completed"}` |
| `system/task_notification` | `output_file` 경로 |
| 안쪽 대화 | `--forward-subagent-text` 를 켜면 서브에이전트의 `assistant`·`user`·도구 호출이 **`parent_tool_use_id` 가 채워진 채로** 인라인으로 온다 (프로브에서 5 개) |
| `result.subagent_stats` | `spawned` · `completed` · `failed` · `max_depth` · `by_type` |

**`parent_tool_use_id` 가 챗 UI 의 중첩 열쇠다.** 그 값이 있는 이벤트는 바깥 도구 카드 안으로 접어 넣고, `null` 인 것만 대화 본문에 둔다.

**설계에 미치는 영향**: 오케스트레이션 위임은 MCP 도구라서 (가) 쪽이다 — v1 의 위임 카드는 "도는 중" 과 경과 시간까지만 보인다. 그 이상을 원하면 진행을 **승인 브리지가 이미 쓰는 앱 소켓으로** 밀어야 한다. 5.7 이 그 판단이다.

### 3.11 큐잉 — 도는 중에 보낸 메시지

턴이 도는 중에 사용자가 또 치면 어떻게 되는가. 긴 글을 시켜놓고 1.5 초 뒤와 1.8 초 뒤에 짧은 질문 둘을 밀어 넣었다.

| 보낸 것 | 받은 `result` |
|---|---|
| 3 개 | **2 개** |

둘째와 셋째가 **한 턴으로 합쳐졌다** — 답이 `"빨강\n노랑"` 이었다. 첫 턴은 끝까지 갔다.

**챗 UI 는 이것을 그대로 반영해야 한다.** 사용자 말풍선은 셋이지만 답은 둘이다. 입력창을 턴 동안 잠그는 길도 있지만, 잠그지 않는 편이 낫다 — `claude` 가 이미 큐를 갖고 있고, 잠그면 사용자가 생각난 것을 적어둘 자리가 사라진다. 다만 **큐에 들어간 메시지는 답이 아직 없다는 것을 화면이 말해야 한다**.

`control_response` 의 `still_queued` 와 `result.queued_turn_count` 가 이 상태를 볼 통로인데, 프로브에서는 둘 다 비어 있었다(중단 시점에 큐가 비어 있었다). **큐 깊이를 화면에 정확히 그리는 것은 v1 범위 밖으로 둔다** — 앱이 자기가 보낸 것과 받은 `result` 수를 세는 것으로 충분하다.

### 3.12 부수 이벤트 — 챗이 보여줄 수 있는 것

`result` 한 벌에 들어 있는 것 중 화면에 값이 있는 것들이다.

| 필드 | 예 |
|---|---|
| `total_cost_usd` | `0.28292174999999997` |
| `usage.input_tokens` · `output_tokens` · `cache_read_input_tokens` · `cache_creation_input_tokens` | 캐시 적중이 따로 온다 |
| `modelUsage.<모델>.contextWindow` · `maxOutputTokens` | `1000000` · `64000` |
| `duration_ms` · `duration_api_ms` · `ttft_ms` | 첫 글자까지 걸린 시간이 따로 |
| `num_turns` · `stop_reason` · `terminal_reason` | `end_turn` · `completed` · `aborted_streaming` |
| `permission_denials` | 3.6 |
| `subagent_stats` | 3.10 |
| `api_error_status` | 정상이면 `null` |

`rate_limit_event` 는 턴마다 온다.

```json
{"rate_limit_info":{"status":"allowed","rateLimitType":"five_hour",
 "unifiedWindows":{"five_hour":{"utilization":0.43,"resetsAt":1788513600},
                   "seven_day":{"utilization":0.04,"resetsAt":1789074000}}}}
```

**한도 사용률을 창 두 개로 준다.** 지금 앱에는 이 정보가 닿을 길이 아예 없다.

컨텍스트 사용량은 `/context` 로 물을 수 있다(3.13). 컨텍스트가 찰 때의 경고나 자동 압축 이벤트는 **이번 실측에서 만들지 못했다** — 짧은 프로브로는 100 만 토큰 창을 채울 수 없다. 10 절의 열린 질문이다.

### 3.13 슬래시 명령 — 이 모드에서도 돈다

**터미널 뷰의 처지를 가르는 물음이라 따로 쟀다.**

사용자 메시지 텍스트를 그냥 `/probefp` 로 보냈다. 가짜 레포의 `.claude/commands/probefp.md` 에 지문을 적어둔 명령이다.

| 보낸 것 | 결과 |
|---|---|
| `/probefp` (레포의 커스텀 명령) | ✅ `SLASHFP-9090` — 지문 그대로 |
| `/context` (내장 로컬 명령) | ✅ 마크다운 표가 `assistant` 텍스트로 온다. `result.num_turns` 가 **0** — 모델을 부르지 않았다 |

`init` 의 `slash_commands` 배열에 이 세션에서 쓸 수 있는 것 74 개가 실려 있다. **챗 UI 는 그 배열로 자동완성을 만들 수 있고, 실행은 텍스트를 그대로 보내는 것으로 끝난다.**

`/context` 가 마크다운 표를 돌려준 것이 특히 크다 — 마크다운 렌더러 하나가 이런 명령들의 화면을 통째로 덮는다.

### 3.14 `--replay-user-messages` — 전사의 출처를 하나로

`--replay-user-messages` 를 켜면 앱이 보낸 사용자 메시지가 `user` 이벤트로 되돌아온다.

```
system/init  →  rate_limit_event  →  user["1 더하기 1은? 숫자만."]  →  assistant["2"]  →  result
```

**켠다.** 켜지 않으면 화면의 전사가 두 곳에서 온다 — 사용자 말풍선은 앱이 직접 넣고 나머지는 스트림에서 온다. 그 둘의 순서가 큐잉(3.11)이나 중단이 끼는 순간 어긋나고, 어긋난 것은 사용자가 스크롤을 올려 읽을 때 드러난다. **전사는 한 물줄기에서만 온다.**

### 3.15 마크다운 렌더링 수단 — 문서 조사

코드 실행 없이 문서·저장소·Maven Central 메타데이터를 조사했다. 값은 전부 출처에서 확인한 것이다.

| 후보 | 최신 판 | 배포일 | Desktop(JVM) | Material 3 | 코드 하이라이트 | 스트리밍 API |
|---|---|---|---|---|---|---|
| **mikepenz/multiplatform-markdown-renderer** | **0.45.0** | 2026-08-28 | ✅ 정식 타깃 | ✅ `-m3` 모듈 | ✅ `-code` 모듈 | ✅ 전용 API |
| halilozercan/compose-richtext | 1.0.0-alpha05 | 2026-06-08 | ✅ | ✅ `-material3` | 확인 못 함 | 확인 못 함 |
| JetBrains Jewel (markdown) | 0.40.0-262.10315.125 | 2026-09-02 | ✅ JVM 전용 | ❌ IntelliJ L&F | Jewel 자체 | 확인 못 함 |
| huarangmeng/KMP Markdown | 1.5.2 | 2026-08-27 | ✅ | ❌ 자체 테마 | ✅ 내장 | ✅ `isStreaming` |
| `org.jetbrains:markdown` (파서만) | 0.7.10 | 2026-09-02 | ✅ | 해당 없음 | 해당 없음 | ✅ `StreamingMarkdownFile` |

**추천: mikepenz 0.45.0.** 좌표 셋이다.

```kotlin
implementation("com.mikepenz:multiplatform-markdown-renderer-jvm:0.45.0")
implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")
implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.45.0")
```

**스트리밍 API 가 이 선택의 결정적 이유다.** `rememberStreamingMarkdownState()` 는 append-only 이고, 내부적으로 확정 구간(`stableAst`)과 미확정 꼬리(`unstableAstTail`)를 갈라 꼬리만 다시 파싱한다. 닫히지 않은 코드 펜스가 꼬리에 머물다가 펜스가 닫히면 확정으로 넘어간다 — 3.3 의 글자 단위 델타를 매번 전체 재파싱하는 순진한 구현의 제곱 비용을 피한다. 그 아래는 `org.jetbrains:markdown` 의 `StreamingMarkdownFile` 이다.

**함정 둘을 지금 적어둔다.**

1. **0.13.0 부터 core 는 Material 테마에 의존하지 않는다.** `-m3` 를 함께 넣고 `com.mikepenz.markdown.m3.Markdown` 을 import 해야 기본 스타일이 붙는다.
2. **하이라이터(`dev.snipme:highlights:1.1.0`)의 언어 매칭이 enum 이름 완전일치다.** 지원 언어가 17 개인데 `json`·`yaml`·`xml`·`sql`·`diff` 가 없고, `bash`·`js`·`py`·`ts` 같은 흔한 별칭도 매칭에 실패한다. **펜스 언어 문자열을 정규화하는 얇은 매핑을 우리가 둔다.** 이것은 두 번째 사용처를 기다릴 추상화가 아니라 라이브러리의 알려진 결함을 메우는 한 함수다.

`highlights` 는 최신 판이 2025-09-02 이고 2026 년 커밋이 2 건이다 — 사실상 정체다. 대안(`KodeView`·`NeoUtils/Highlight`)이 더 활발하지도 않고 `-code` 모듈과의 통합 이점이 커서 그대로 쓴다. **이 판단은 하이라이트가 안 되는 것이 기능 실패가 아니라 색이 덜 붙는 것이기 때문에 성립한다.**

---

## 4. 설계 원칙 — 상속과 추가 둘

workdir 4 절의 원칙 1~6(원칙 5 는 app-owned 가 개정), app-owned 의 원칙 11·12, orchestration 의 원칙 13 을 그대로 상속한다. 이 설계가 특히 계속 부딪히는 셋을 먼저 적어둔다.

> 3. **세션 내부에 개입하지 않는다** — 챗 UI 는 이 원칙의 경계에 선다. 2.1 이 그 경계를 어디에 긋는지 정했다.
> 11. **조립 지식은 엔진에 하나만 둔다** — 챗 모드의 argv 도 `kyu session-command` 가 답한다(5.2).
> 13. **위임이 메인보다 넓은 권한을 갖지 않는다** — 승인 브리지가 이 원칙의 새 통로가 된다(5.4).

**추가**

14. **전사는 한 물줄기에서만 온다** — 화면에 보이는 대화의 모든 조각은 `claude` 의 출력 스트림에서 나온다. 사용자가 방금 친 말도 마찬가지다(3.14). 앱이 자기가 아는 것을 스트림에 섞어 넣기 시작하면 순서가 어긋날 자리가 생기고, 그 어긋남은 사용자가 스크롤을 올릴 때까지 드러나지 않는다.

15. **못 그리는 것을 그린 척하지 않는다** — 이벤트로 오지 않는 것은 화면에 없다. 위임 진행이 그 예다(3.10). 앱이 추측으로 진행 막대를 그리면 그 막대는 사실이 아니고, 사용자는 그것이 사실이 아니라는 것을 위임이 실패했을 때 알게 된다. **모르는 것은 "도는 중" 으로 둔다.**

---

## 5. 아키텍처

### 5.1 배치

```
┌─ 데스크톱 앱 (Compose Multiplatform) ────────────────────────────┐
│                                                                 │
│  화면          ChatTranscriptPane · ToolCallCard ·               │
│                PermissionRequestCard · ChatComposer   (6 절)     │
│                        ↑ 상태                                    │
│  상태 홀더     ChatSessionStateHolder            (5.5)           │
│                        ↑ 포트                                    │
│  포트          ChatSessionOpener · SessionCommandSource ·        │
│                PermissionRequestSource           (5.3 · 5.4)     │
│                        ↑ 어댑터                                  │
│  어댑터        ProcessChatSession (파이프 + JSON 줄)  (5.3)      │
│                PermissionBridgeServer (유닉스 소켓 수신) (5.4)    │
└──────────────────┬───────────────────────┬──────────────────────┘
                   │ argv 를 묻는다        │ 소켓
                   ↓                       │
        ┌── kyu session-command ──┐        │
        │   --json --chat         │        │
        └──────────┬──────────────┘        │
                   │ 답한 argv 를 실행     │
                   ↓                       │
        ┌── claude (파이프 3 개) ──────────┐│
        │   stdin  ← 사용자 메시지·제어    ││
        │   stdout → 이벤트 스트림         ││
        │   stderr → 진단                  ││
        │      │ 자식으로 띄운다           ││
        │      ├─ kyu mcp serve <workdir>  ││  (메인 세션만 — 위임 도구)
        │      └─ kyu mcp ask <소켓 경로> ─┼┘  (모든 챗 세션 — 승인 도구)
        └──────────────────────────────────┘
```

**바뀌는 것은 앱 안쪽뿐이다.** 엔진은 여전히 답만 하고, `claude` 는 여전히 앱의 자식이고, 레포 설정은 여전히 cwd 에서 온다. 새로 생기는 것은 **`kyu mcp ask` 라는 두 번째 MCP 서버 하나와 그것이 부모에게 묻는 소켓 하나**다.

### 5.2 엔진 표면 — `session-command` 에 챗 모드를 더한다

원칙 11 그대로다. 챗 모드의 argv 도 엔진이 조립한다.

```
kyu session-command [repo] --json --chat --approval-socket <경로>
```

**왜 앱이 소켓 경로를 넘기는가.** 소켓을 여는 것은 앱이다 — 앱이 결정을 내리는 쪽이므로. 엔진은 그 경로를 argv 의 어느 자리에 넣을지만 안다. 엔진이 경로를 스스로 정하면 앱이 그것을 되읽어야 하고, 그러면 답 문서에 필드가 하나 늘면서 "엔진이 정하고 앱이 따른다" 와 "앱이 정하고 엔진이 옮긴다" 가 섞인다.

조립되는 것(메인 세션):

```
claude -p
  --input-format stream-json --output-format stream-json --verbose
  --include-partial-messages          # 3.3
  --replay-user-messages              # 3.14 · 원칙 14
  --session-id <UUID> | --resume <UUID>
  --permission-prompt-tool mcp__kyu-ask__request_permission
  --mcp-config '{"mcpServers":{"kyu":{…serve…},"kyu-ask":{…ask…}}}'
  --add-dir <레포 절대경로>...
```

레포 세션은 `--add-dir` 이 없고 `--mcp-config` 에 `kyu` 오케스트레이션 서버가 빠진다 — orchestration 5.2.1 이 정한 그대로다. `kyu-ask` 는 **둘 다** 받는다. 승인은 위임의 문제가 아니라 화면의 문제라서다.

**`--bypass-permissions` 가 켜져 있으면 `kyu-ask` 를 등록하지 않고 `--permission-prompt-tool` 도 붙이지 않는다.** 3.6 이 `bypassPermissions` 에서 승인 도구가 한 번도 불리지 않는 것을 보였다 — 등록해봐야 프로세스 하나가 놀 뿐이다. 붙이지 않는 편이 "bypass 로 열었는데 승인 카드가 뜰지도 모른다" 는 물음 자체를 없앤다.

**`--permission-mode` 는 넣지 않는다.** 지금 터미널 세션이 사용자 설정을 상속하는 것과 같게 둔다. 챗 모드가 관문을 강제로 켜면 사용자가 자기 `settings.json` 에 적어둔 선택이 화면 종류에 따라 달라지고, 그것은 이 전환이 바꿀 일이 아니다. **승인 카드는 관문이 열릴 때 뜨고, 관문이 언제 열리는지는 계속 사용자 설정이 정한다.**

#### 5.2.1 판(schemaVersion)은 올리지 않는다

답 문서(`session_command_json.go`)의 모양은 그대로다 — `command`·`cwd`·`env`·`resumedConversationId`.

담기는 값이 달라진 것은 **조립이 바뀐 것이지 문서의 모양이 바뀐 것이 아니다.** 같은 판단을 orchestration 5.2 가 `--mcp-config` 를 더할 때 이미 했다. 판 규약은 그대로다 — 필드를 빼거나 이름·의미를 바꿀 때만 올린다.

**앱이 모드를 헷갈릴 자리도 없다.** `--chat` 을 붙여 물은 것은 앱이고, 답을 파이프로 실행할지 PTY 로 실행할지는 물은 쪽이 이미 안다.

#### 5.2.2 `kyu mcp ask` — 세 번째 하위 명령

```
kyu mcp <serve|approve|ask>
```

| 하위 명령 | 하는 일 | 누가 부르나 |
|---|---|---|
| `serve <워크디렉토리>` | 오케스트레이션 도구(`list_repos`·`run_in_repo`) | `claude` 가 자식으로 |
| `approve <repo>` | 레포의 실행 유발 설정을 사람에게 보이고 승인받는다 | 사람이 터미널에서 |
| `ask <소켓 경로>` | **승인 물음을 앱에게 중계한다** | `claude` 가 자식으로 |

**`approve` 와 `ask` 의 이름이 가깝다.** 둘 다 승인이지만 대상이 다르다 — `approve` 는 **레포를 한 번 승인**하는 것이고(orchestration 5.6), `ask` 는 **도구 호출 하나를 그때그때 묻는** 것이다. 이 가까움이 실제로 헷갈림을 낳는지는 10 절의 열린 질문으로 둔다. `ask` 를 `relay-permissions` 처럼 길게 두는 길도 있는데, 이 이름은 사용법 문구에만 나오고 사람이 칠 일이 없어서 짧은 쪽을 먼저 시도한다.

`ask` 가 여는 도구는 하나다.

| 도구 | 인자 | 답 |
|---|---|---|
| `request_permission` | `tool_name` · `input` · `tool_use_id` (3.5 가 잰 그대로) | `{"behavior":"allow","updatedInput":{…}}` 또는 `{"behavior":"deny","message":"…"}` |

이 도구는 **모델에게 보이지 않는다** — 3.5 가 잰 사실이고, `--permission-prompt-tool` 로 지정되는 한 그렇다.

**서버 이름의 하이픈은 도구 이름에 그대로 남는다.** `kyu-ask` 라는 이름으로 서버를 붙였더니 도구가 `mcp__kyu-ask__approve` 로 보였다 — 콜론이 밑줄로 바뀌는 것(`plugin:context7:context7` → `plugin_context7_context7`)과 다르다. 그래서 `--permission-prompt-tool` 에 넘길 문자열은 `mcp__kyu-ask__request_permission` 그대로다. **이것을 재보지 않고 밑줄로 적었으면 승인 도구가 한 번도 안 불리고, 그 실패는 "관문이 원래 잘 안 열리나 보다" 로 오래 숨었을 자리다.**

### 5.3 Kotlin 어댑터 — 프로세스와 이벤트 모델

#### 5.3.1 포트

```kotlin
/** 앱이 챗 세션 하나를 여는 통로. PTY 를 여는 SessionTerminalOpener 와 같은 자리다. */
interface ChatSessionOpener {
    suspend fun openChatSession(plan: SessionEntryPlan): OpenedChatSession
}

/** 열린 세션 하나. */
interface OpenedChatSession {
    val events: Flow<ChatSessionEvent>
    suspend fun sendUserMessage(text: String)
    suspend fun interruptCurrentTurn()
    suspend fun endSession()
}
```

`SessionEntryPlan` 은 이미 있다(`desktop/src/main/kotlin/com/kyuchestration/desktop/terminal/SessionEntryPlan.kt`) — `kyu session-command` 의 답을 담는 타입이다. 챗 모드에서도 같은 타입을 쓴다. **엔진이 답한 argv·cwd·env 를 실행한다는 사실은 안 바뀌고, 그것을 PTY 에 넣느냐 파이프에 넣느냐만 다르다.**

#### 5.3.2 이벤트 모델 — sealed interface

스트림의 JSON 을 그대로 화면에 흘리지 않는다. 화면이 `event.subtype == "task_progress"` 같은 문자열을 알기 시작하면 `claude` 의 판이 바뀔 때 고칠 자리가 화면 곳곳이 된다.

```kotlin
sealed interface ChatSessionEvent {
    /** system/init — 턴마다 온다. 세션 시작이 아니다(3.1). */
    data class SessionDescribed(
        val conversationId: String,
        val modelName: String,
        val availableSlashCommands: List<String>,
        val connectedMcpServers: List<McpServerStatus>,
    ) : ChatSessionEvent

    /** --replay-user-messages 가 되돌려준 사용자 메시지(3.14 · 원칙 14). */
    data class UserMessageEchoed(val text: String) : ChatSessionEvent

    data class AssistantTextArrived(val text: String, val parentToolUseId: String?) : ChatSessionEvent
    data class AssistantThinkingArrived(val text: String, val parentToolUseId: String?) : ChatSessionEvent

    /** stream_event 의 글자 조각. 완성본은 AssistantTextArrived 로 다시 온다(3.3). */
    data class AssistantTextStreaming(val chunk: String) : ChatSessionEvent

    data class ToolCallRequested(
        val toolUseId: String, val toolName: String,
        val input: JsonObject, val parentToolUseId: String?,
    ) : ChatSessionEvent

    data class ToolCallAnswered(
        val toolUseId: String, val failed: Boolean,
        val modelVisibleText: String, val typedResult: JsonElement?,
    ) : ChatSessionEvent

    /** system/task_* — 서브에이전트만 온다. MCP 도구는 오지 않는다(3.10). */
    data class DelegationProgressed(
        val taskId: String, val toolUseId: String,
        val description: String, val lastToolName: String?,
    ) : ChatSessionEvent

    data class TurnFinished(
        val outcome: TurnOutcome,              // Completed · Interrupted · Failed
        val costUsd: Double, val usage: TurnUsage,
        val permissionDenials: List<PermissionDenial>,
    ) : ChatSessionEvent

    data class UsageWindowsObserved(val windows: List<RateLimitWindow>) : ChatSessionEvent

    /** stderr 한 줄, 또는 0 이 아닌 종료 코드(3.9 의 resume 실패가 여기로 온다). */
    data class EngineSpoke(val line: String) : ChatSessionEvent
    data class SessionEnded(val exitCode: Int) : ChatSessionEvent
}
```

**`AssistantTextStreaming` 과 `AssistantTextArrived` 를 가르는 이유**: 조각은 화면을 부드럽게 하려고 있는 것이고 전사의 사실은 완성본이다. 상태 홀더는 조각을 임시 버퍼에 쌓다가 완성본이 오면 버퍼를 버리고 완성본으로 바꾼다. **조각만으로 전사를 만들면 중단된 턴의 잘린 문장이 영구히 전사에 남는다.**

`parentToolUseId` 가 채워진 이벤트는 그 도구 카드 안으로 접힌다(3.10).

#### 5.3.3 어댑터

`ProcessChatSession` 이 `ProcessBuilder` 로 프로세스를 띄우고 파이프 셋을 읽고 쓴다. `PtySessionTerminalOpener` 가 있는 자리(`desktop/src/main/kotlin/com/kyuchestration/desktop/terminal/pty/`) 옆에 `chat/` 을 둔다.

세 가지를 이 어댑터 하나가 안다.

| 아는 것 | 왜 여기인가 |
|---|---|
| JSON 줄 ↔ `ChatSessionEvent` 매핑 | 이 매핑을 아는 곳이 하나여야 판이 바뀔 때 고칠 자리가 하나다 |
| stdin 에 쓰는 두 모양 — 사용자 메시지와 `control_request` | 3.1 · 3.8 |
| stdout 이 아무 때나 끊길 수 있다는 것 | `claude` 가 죽으면 `SessionEnded` 로 바꾼다 |

**stdout 읽기는 전용 코루틴 하나가 한다.** 파이프 버퍼가 차면 자식이 쓰기에서 멎는데, 이 앱은 그 함정을 이미 한 번 만났다 — `EmbeddedTerminalStateHolder` 의 주석이 *"아무 위젯도 그 통로를 읽지 않는 세션이 하나 생기고, PTY 버퍼가 차는 순간 그 안의 `claude` 가 쓰기에서 멎는다"* 라고 적어두었다(`EmbeddedTerminalStateHolder.kt:63`). **챗 모드에서도 화면에 안 보이는 세션의 stdout 을 계속 읽어야 한다.**

### 5.4 권한 브리지 — 채널을 무엇으로 할 것인가

`kyu mcp ask` 는 `claude` 의 자식이고 결정은 앱에 있다. 셋을 검토했다.

| 후보 | 성립하나 | 판단 |
|---|---|---|
| **유닉스 도메인 소켓** | ✅ 3.7 에서 150 초 왕복까지 쟀다 | **채택** |
| TCP 루프백 | 성립하지만 같은 머신의 다른 프로세스도 붙는다. 막으려면 비밀 토큰이 필요하고, 그 토큰은 `--mcp-config` 를 타고 argv 에 실려 `ps` 에 보인다 — orchestration 5.2 가 이미 *"이 문자열은 프로세스 인자로 들어가 `ps` 에 보이므로, 비밀이 생기면 이 자리를 다시 정해야 한다"* 라고 적어두었다. 그 날이 이 날이 되게 하지 않는다 | 기각 |
| 파일 폴링 | 요청 파일을 쓰고 답 파일을 기다린다. 지연이 폴링 주기만큼 붙고, 앱이 죽었는지 아직 고민 중인지 구분할 방법이 없다 | 기각 |

**윈도우 전망**: 이 채널은 윈도우 네이티브 목표를 막지 않는다.

| 쪽 | 수단 | 근거 |
|---|---|---|
| 앱(JVM) 이 듣는다 | `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` | JDK 16 이상. 이 프로젝트는 툴체인을 21 로 고정해 두었다(`desktop/build.gradle.kts`) |
| 엔진(Go) 이 건다 | `net.Dial("unix", …)` | Go 는 윈도우에서도 `AF_UNIX` 를 지원한다 |

윈도우 10 1803 이상이면 `AF_UNIX` 가 OS 에 있다. **양쪽 다 한 벌의 코드로 리눅스·맥·윈도우를 덮는다** — 명명 파이프를 따로 만들 이유가 지금은 없다. 원칙 4 그대로다.

**소켓 자리는 워크디렉토리가 아니다.** 3.7 이 잰 107 바이트 한계 때문이다.

| OS | 자리 |
|---|---|
| 리눅스 | `$XDG_RUNTIME_DIR/kyuchestration/<세션 짧은 id>.sock` (없으면 `/tmp/kyuchestration-$UID/`) |
| 맥 | `$TMPDIR/kyuchestration/<세션 짧은 id>.sock` |
| 윈도우 | `%LOCALAPPDATA%\Kyuchestration\run\<세션 짧은 id>.sock` |

파일 권한은 `0600` 으로 만든다. 윈도우에는 그 개념이 없고 담는 디렉토리의 ACL 이 그 몫을 한다 — 사용자별 `%LOCALAPPDATA%` 아래 두는 이유가 그것이다.

**세션마다 소켓 하나다.** 하나를 공유하면 어느 세션의 물음인지를 메시지 안에 담아야 하고, 그러면 그 식별자를 누가 정하고 누가 검증하는지가 새로 생긴다. 세션마다 열면 **연결 자체가 곧 어느 세션인지다.**

#### 5.4.1 무엇이 오가는가

```
kyu mcp ask  →  앱 :  {"toolName":"Write","input":{…},"toolUseId":"toolu_…"}
앱  →  kyu mcp ask :  {"decision":"allow","updatedInput":{…}}
                   |  {"decision":"deny","reason":"사용자가 거절했습니다"}
```

**줄 단위 JSON 이다.** `kyu` 의 MCP 서버가 이미 줄 단위 JSON-RPC 를 말하고(`internal/mcpserver/protocol.go`), 새 인코딩을 들일 이유가 없다.

**앱이 답하지 않으면 어떻게 되는가.** 앱이 죽으면 소켓이 닫히고 `ask` 는 읽기에서 EOF 를 받는다. 그때 `deny` 로 답한다 — 앱이 사라진 뒤에 도구가 실행되는 것보다 낫다. 앱이 살아 있는데 사람이 답하지 않는 것은 **막지 않는다**. 3.7 이 150 초를 쟀고, 상한을 우리가 정하면 그 숫자보다 오래 고민한 사용자가 이유 없이 거절당한다.

#### 5.4.2 원칙 13 이 여기서 다시 걸린다

*"위임이 메인보다 넓은 권한을 갖지 않는다."*

위임(`run_in_repo`)은 자기 `claude` 를 띄운다(orchestration 5.4). 그 안쪽 `claude` 가 승인이 필요한 일을 하면 지금은 `--permission-mode auto` 나 bypass 표식으로 처리된다. **챗 브리지가 생겼다고 위임의 승인을 사용자에게 올리지 않는다.** 올리면 사용자는 자기가 안 보는 레포에서 나온 물음에 답하게 되고, 그 물음의 맥락은 화면에 없다.

**v1 에서 승인 브리지는 사용자가 보고 있는 세션의 것만 나른다.** 위임 안쪽의 권한 정책은 orchestration 5.4.1 그대로 둔다. 10 절의 열린 질문이다.

### 5.5 상태 홀더 — 세션마다 대화 하나

`EmbeddedTerminalStateHolder` 가 앉은 자리에 `ChatSessionStateHolder` 가 선다. 그 홀더가 쥐는 것이 늘어난다 — 지금은 `TtyConnector` 하나였고, 이제는 대화 전체다.

```kotlin
data class ChatConversation(
    val target: SessionTarget,
    val conversationId: String?,          // system/init 이 알려준다
    val resumedConversationId: String?,   // 3.9 · app-owned 5.5.4
    val entries: List<ChatEntry>,         // 전사
    val streamingText: String?,           // 3.3 의 조각 버퍼 — 완성본이 오면 버린다
    val turnState: TurnState,             // Idle · Running · Interrupting
    val pendingPermission: PermissionRequest?,
    val lastTurnCost: TurnCost?,
    val usageWindows: List<RateLimitWindow>,
)
```

`ChatEntry` 는 화면에 그릴 단위다 — 사용자 말풍선, 모델 말풍선, 사고 블록, 도구 카드(안에 자식 항목들), 오류. `ToolCallRequested` 가 카드를 만들고 같은 `toolUseId` 의 `ToolCallAnswered` 가 그 카드를 채운다.

**보유는 그대로다.** `EmbeddedTerminalStateHolder` 의 판단 — *"카드를 옮겨도 앞 세션은 계속 돈다"*(`EmbeddedTerminalStateHolder.kt:18`) — 이 챗에서도 성립하고, 오히려 더 쉬워진다. 터미널 시절에는 화면에 없는 세션의 위젯을 살려둬야 상태를 안 잃었지만, 챗에서는 전사가 데이터로 홀더 안에 있어서 화면이 없어도 아무것도 잃지 않는다. **살려둬야 하는 것은 stdout 을 읽는 코루틴 하나뿐이다**(5.3.3).

**전사를 어디까지 들고 있을 것인가.** v1 은 메모리에만 둔다. 앱을 닫으면 사라지고, 다시 열면 `--resume` 으로 대화는 이어지지만 **화면의 전사는 비어 있다** — `claude` 는 앞 대화를 알지만 앱은 모른다. 이 어긋남을 화면이 먼저 말한다(원칙 12): 이어가기로 연 챗의 맨 위에 "이 대화에는 앞선 내용이 있습니다 — 화면에는 이번 실행부터 보입니다" 를 둔다. **전사를 파일로 남기는 것은 v1 밖이다**(10 절).

### 5.6 대화 이어가기와 `conversations.json`

바뀌는 것이 없다. `kyu session-command` 가 `.coord/conversations.json` 에서 대화를 정하고(`internal/cli/session_command.go` 의 `conversationForLabel`), `--session-id` 또는 `--resume` 을 argv 에 넣어 답한다. 챗 모드도 같은 답을 받아 같은 플래그로 실행한다 — 3.9 가 그 모드에서도 도는 것을 쟀다.

**세션 지도의 낱말이 하나 더 갈릴 뻔했다.** `workdir.SessionConversation` 과 `workdir.DelegationConversation` 이 있는데(orchestration 5.5.1), 챗 세션은 **`SessionConversation` 그대로**다. 화면이 터미널이든 챗이든 사용자가 여는 그 세션이고, 대화 하나를 두 화면이 동시에 열면 `--session-id already in use` 로 거절되는 것도 그대로다. **낱말을 늘리지 않는다.**

### 5.7 위임이 메인 챗에 보이는 길

3.10 이 잰 사실 그대로 그린다.

**v1 — 도구 카드 하나.** 메인 세션이 `run_in_repo` 를 부르면 `ToolCallRequested(toolName = "mcp__kyu__run_in_repo")` 가 오고, 몇 분 뒤 `ToolCallAnswered` 가 온다. 그 사이 카드가 보여줄 수 있는 것은 셋이다.

| 보이는 것 | 출처 |
|---|---|
| 어느 레포에 무엇을 시켰나 | `input.repo` · `input.task` |
| 경과 시간 | 앱이 잰다 — 두 이벤트 사이 |
| 끝난 뒤의 결과와 원출력 파일 | `ToolCallAnswered` · orchestration 5.4.3 의 `.coord/runs/` |

**진행 막대는 없다.** 원칙 15 다. MCP 진행 알림이 오지 않는 것을 쟀고, 오지 않는 것을 그린 척하지 않는다.

**두 번째 걸음의 후보를 적어둔다 — 지금 만들지 않는다.** `kyu mcp serve` 가 위임 진행을 **승인 브리지가 이미 쓰는 앱 소켓으로** 밀어 넣는 길이 있다. 채널이 이미 있으므로 새로 만들 것은 메시지 종류 하나뿐이고, 그것이 원칙 4 가 말하는 "두 번째 사용처" 다. 다만 **첫 번째 사용처(승인)가 실제로 서기 전에는 만들지 않는다.** 승인 브리지를 쓰면서 채널의 모양이 바뀔 수 있고, 그때 사용처가 둘이면 둘 다 고쳐야 한다.

**서브에이전트는 다르다.** 메인 세션이 `Task` 를 띄우면 `system/task_*` 가 오고 `--forward-subagent-text` 로 안쪽 대화까지 온다. **그것은 v1 부터 그린다** — 이벤트가 오는 것을 그리는 데는 아무 결정도 필요 없다.

### 5.8 마크다운을 어디서 그리는가

`AssistantTextArrived` 의 텍스트와 `/context` 같은 로컬 명령의 답이 마크다운이다(3.13).

- 완성된 텍스트는 `com.mikepenz.markdown.m3.Markdown` 으로 그린다.
- 스트리밍 중에는 `rememberStreamingMarkdownState()` 에 조각을 append 한다(3.15).
- 코드 펜스의 언어 문자열은 우리 매핑을 한 번 거쳐 넘긴다 — `bash`→`SHELL`, `js`→`JAVASCRIPT` 등. 매핑에 없으면 하이라이트 없이 그린다.
- **아주 긴 코드 블록이 스트리밍되는 동안에는 하이라이트를 끈다.** `-code` 모듈은 코드 문자열을 키로 하이라이팅을 다시 도는데, 블록이 한 글자 늘 때마다 전체를 다시 칠한다. 블록이 닫힌 뒤 하이라이트 컴포넌트로 교체한다.

**도구 결과는 마크다운으로 그리지 않는다.** Bash 의 출력이나 파일 내용에 마크다운 문법처럼 보이는 글자가 들어 있을 수 있고, 그것을 렌더링하면 화면이 원본과 달라진다. 도구 결과는 고정폭 평문이다.

---

## 6. UI 설계 — Material 3

> **이 절은 구조까지만 정한다.** 사용자에게 별도 시각 목업이 가는 중이고, 색상 세부는 목업이 확정된 뒤 이 절을 갱신한다. 지금 정하는 것은 **토큰이 놓일 자리**·**셸의 골격**·**컴포넌트의 목록**이다.

### 6.1 테마 — 지금 비어 있는 한 줄을 채운다

`MaterialTheme { }`(`KyuchestrationDesktopScreen.kt:91`)이 셋을 받게 된다.

```kotlin
MaterialTheme(
    colorScheme = if (darkTheme) KyuDarkColorScheme else KyuLightColorScheme,
    typography = KyuTypography,
    shapes = KyuShapes,
) { … }
```

| 토큰 | v1 의 자세 |
|---|---|
| **색** | 시드 색 하나에서 라이트·다크 두 스킴을 만든다. Material 3 의 `SurfaceContainer` 층(`surfaceContainerLowest`~`Highest`)을 실제로 쓴다 — 챗의 깊이(배경 / 말풍선 / 도구 카드 / 카드 안 코드)가 정확히 이 층에 대응한다 |
| **다크 모드** | v1 부터 넣는다. 데스크톱은 OS 설정을 읽을 표준 통로가 없으므로 **창의 토글 하나**로 두고 선택을 남긴다 |
| **타이포** | Material 3 의 스케일을 그대로 쓰되 **본문과 고정폭 두 계열**을 정한다. 대화 본문은 비례폭, 코드·도구 출력·경로는 고정폭 |
| **셰이프** | 말풍선과 카드가 다른 반경을 갖는다 — 대화의 것과 기계의 것을 모양으로 가른다 |
| **밀도** | 창 폭에 따라 두 단계. 좁으면 도구 카드가 접힌 채로 시작한다 |

**한글 폰트를 지금 정해둔다.** 지금은 시스템 기본이고, 리눅스와 윈도우에서 다르게 보인다. 목업이 확정될 때 함께 정한다.

### 6.2 레이아웃 셸 — 좌우 3 분할

지금은 위아래다. 그 근거가 `KyuchestrationDesktopScreen.kt:157` 의 주석에 있다.

> *"좌우가 아니라 위아래인 이유는 터미널이 폭을 먹기 때문이다. 세션 안의 화면은 80 칸을 기준으로 그려지는데, 창을 반으로 갈라 오른쪽에 두면 그 폭이 나오지 않아 줄이 접힌다."*

**챗은 폭에 맞춰 흐른다.** 그 제약이 사라지므로 좌우로 나눈다.

```
┌──────────┬────────────────────────────────┬──────────────────┐
│ 내비게이션 │  대화                          │  상세            │
│ 레일      │                                │                  │
│          │  ┌──────────────────────────┐  │  선택한 도구      │
│ 워크디렉토리│  │ 사용자 말풍선             │  │  호출의 전문      │
│ ─────────│  └──────────────────────────┘  │  (전체 명령 ·    │
│ ● 메인    │  ┌──────────────────────────┐  │   전체 diff ·   │
│ ○ proj-a │  │ 모델 말풍선 (마크다운)     │  │   전체 출력)    │
│ ○ proj-b │  ├──────────────────────────┤  │                  │
│ ○ proj-c │  │ ▸ Bash  git status       │  │  또는 세션 정보   │
│          │  │   ✓ 12 줄               │  │  (모델 · MCP ·   │
│ ─────────│  ├──────────────────────────┤  │   컨텍스트 · 한도)│
│ + 클론    │  │ ⚠ 권한 요청              │  │                  │
│          │  │   [허용] [수정] [거절]    │  │                  │
│          │  └──────────────────────────┘  │                  │
│          │  ┌──────────────────────────┐  │                  │
│          │  │ 입력창          [중단]    │  │                  │
│          │  └──────────────────────────┘  │                  │
└──────────┴────────────────────────────────┴──────────────────┘
```

| 자리 | 담는 것 |
|---|---|
| **내비게이션 레일** | 워크디렉토리 이름, 메인 + 레포 세션 목록. 각 항목에 `kyu list --json` 이 주는 상태(`DIRTY`·`AHEAD`·`IDLE`)와 앱 세션 보유 표시 — 지금 대시보드 카드가 보이는 것 그대로다 |
| **대화** | 6.3 |
| **상세** | 접을 수 있다. 좁은 창에서는 기본으로 접힌다 |

**대시보드 카드가 레일로 접힌다.** 지금 카드가 보이는 것(레포 상태·계획 작업·최근 위임)은 레일 항목의 보조 줄과 상세 패널로 나뉜다. **카드를 없애는 것이 아니라 대화가 주인공인 셸로 자리를 옮기는 것이다.**

### 6.3 메시지 컴포넌트

| 컴포넌트 | 그리는 이벤트 | 모양 |
|---|---|---|
| `UserMessageBubble` | `UserMessageEchoed` | 오른쪽 정렬, `secondaryContainer`. 답을 아직 못 받은 것은 흐리게(3.11) |
| `AssistantMessageBlock` | `AssistantTextArrived` · `AssistantTextStreaming` | 말풍선 없이 폭 전체. 마크다운(5.8) |
| `ThinkingBlock` | `AssistantThinkingArrived` | 기본 접힘. 헤더에 `system/thinking_tokens` 의 추정 토큰 |
| `ToolCallCard` | `ToolCallRequested` + `ToolCallAnswered` | 아래 표 |
| `PermissionRequestCard` | 브리지가 올린 물음(5.4) | 6.4 |
| `DelegationCard` | `run_in_repo` 호출 | 레포 이름·작업·경과 시간. 진행 막대 없음(5.7) |
| `SubagentCard` | `DelegationProgressed` + `parentToolUseId` 자식들 | 안쪽 대화를 접어 담는다 |
| `TurnFooter` | `TurnFinished` | 비용·토큰·소요 시간 배지. 중단·실패면 그 사유 |
| `PermissionDenialNotice` | `TurnFinished.permissionDenials` | 3.6 의 `dontAsk` 가 여기로 온다 |
| `EngineNotice` | `EngineSpoke` · `SessionEnded` | resume 실패가 여기로 온다(3.9) |

`ToolCallCard` 의 갈래는 도구 이름으로 정한다. **모르는 도구는 일반 카드로 떨어진다** — MCP 도구가 얼마든지 새로 붙을 수 있으므로 이것이 기본값이어야 한다.

| 갈래 | 접힌 모습 | 펼친 모습 |
|---|---|---|
| Bash | 명령 한 줄 + 종료 표시 | 전체 명령과 출력(고정폭) |
| Edit · Write | 파일 이름 + `+n −m` | diff. 추가·삭제를 `tertiary`·`error` 계열로 |
| Read | 파일 이름 + 줄 수 | 읽은 구간. `tool_use_result.file` 이 준다(3.4) |
| Glob · Grep | 패턴 + 결과 수 | 경로 목록 |
| MCP 도구 | `서버 · 도구` + 결과 한 줄 | 인자 JSON 과 결과 전문 |
| 일반 | 도구 이름 + 성공·실패 | 인자 JSON 과 결과 전문 |

**실패한 도구 호출은 접힌 상태에서도 이유가 보인다.** `is_error: true` 의 `content` 첫 줄을 헤더에 둔다 — 접힌 카드만 보고 무엇이 잘못됐는지 알 수 있어야, 실패가 대화에 묻히지 않는다.

### 6.4 권한 승인 — 인라인 카드

브리지가 물음을 올리면 대화의 맨 아래에 카드가 뜬다. **다이얼로그가 아니라 인라인이다** — 물음의 맥락(직전에 모델이 무엇을 하겠다고 했는지)이 바로 위에 있어야 사용자가 판단할 수 있고, 다이얼로그는 그것을 가린다.

| 버튼 | 브리지가 보내는 답 |
|---|---|
| **허용** | `{"decision":"allow","updatedInput":<원본>}` |
| **수정 후 허용** | 인자를 편집한 뒤 `updatedInput` 에 그것을 싣는다 — 3.5 가 실제로 도는 것을 쟀다 |
| **거절** | `{"decision":"deny","reason":"…"}`. 이유를 적을 수 있고, 그 이유가 모델에게 그대로 간다 |

카드가 보여주는 것: 도구 이름, 인자(Write 면 대상 경로와 내용, Bash 면 명령 전문), 그리고 **이 세션의 cwd**. 마지막 것이 중요하다 — 챗이 여럿 열려 있으면 어느 레포의 물음인지가 판단의 절반이다.

**"항상 허용" 을 v1 에 두지 않는다.** 그것을 두면 어딘가에 규칙을 적어야 하고, 그 자리는 사용자의 `settings.json` 이다. 앱이 사용자 설정 파일을 고치기 시작하는 것은 이 전환이 결정할 일이 아니다. 10 절의 열린 질문이다.

### 6.5 입력창

| 항목 | 정한 것 |
|---|---|
| 멀티라인 | Enter 로 보내고 Shift+Enter 로 줄바꿈 |
| 전송 ↔ 중단 | 턴이 도는 동안 같은 자리의 버튼이 중단으로 바뀐다. 중단은 `control_request`(3.8) |
| 턴 중 입력 | 막지 않는다(3.11). 보낸 것은 흐린 말풍선으로 쌓인다 |
| 슬래시 자동완성 | `SessionDescribed.availableSlashCommands` 로 만든다(3.13) |
| 파일 경로 | v1 밖 |

### 6.6 스트리밍 표시

| 상태 | 화면 |
|---|---|
| 요청을 보냈다 (`system/status`) | 입력창 위의 얇은 진행선 |
| 생각 중 (`system/thinking_tokens`) | 추정 토큰이 오르는 사고 블록 헤더 |
| 글자가 오는 중 | 마지막 문단 뒤의 커서. `AssistantTextStreaming` |
| 도구 인자를 만드는 중 | 카드 헤더에 도구 이름만, 인자 자리는 자리표시자 (3.3 — 이름이 먼저 온다) |
| 도구가 도는 중 | 카드에 경과 시간 |
| 끝 (`result`) | 진행선이 사라지고 `TurnFooter` 가 붙는다 |

### 6.7 밀도와 접근성

| 항목 | 정한 것 |
|---|---|
| 대비 | Material 3 의 `on*` 색을 그대로 쓴다. 도구 카드의 성공·실패를 **색만으로 가르지 않는다** — 아이콘과 문구를 함께 |
| 폰트 크기 | 창 설정에서 본문 배율을 정한다. 코드 블록도 함께 따라간다 |
| 키보드 | 대화 목록·레일·입력창을 Tab 으로 돈다. 승인 카드가 뜨면 포커스가 그리로 간다 |
| 긴 출력 | 도구 결과는 접힌 상태에서 상한 줄 수까지만. "전문 보기" 가 상세 패널을 연다 |
| 선택·복사 | 전사 전체가 선택 가능해야 한다. 코드 블록에는 복사 버튼 |

---

## 7. 터미널 뷰의 처지

**결론: 한 단계 동안 "원시 터미널로 보기" 로 남기고, 삭제 조건을 지금 적어둔다.**

원칙은 미사용 코드를 즉시 지우라고 말한다. 그런데 지금 그 코드는 **쓰이고 있고**, 챗이 그 자리를 다 덮는지는 3 절이 아직 답하지 못한 부분이 있다.

| 터미널이 하던 것 | 챗에서 |
|---|---|
| 슬래시 명령 | ✅ 3.13 — 커스텀·내장 둘 다 돈다 |
| 권한 승인 | ✅ 3.5 · 6.4 |
| 도구 호출 보기 | ✅ 더 잘 된다 |
| 중단 | ✅ 3.8 |
| 대화 이어가기 | ✅ 3.9 |
| `/login` · OAuth 흐름 | **❓ 못 쟀다** — 터미널 안에서 브라우저를 열고 코드를 받아 치는 흐름이다 |
| `/mcp` 의 서버 승인 프롬프트 | **❓ 못 쟀다** — orchestration 3.2 가 헤드리스는 그 관문을 안 탄다고 쟀으니 프롬프트 자체가 안 뜰 수 있지만, 그러면 승인이 필요한 서버는 그냥 안 붙는다 |
| 플랜 모드의 승인 화면 | **❓ 못 쟀다** — `ExitPlanMode` 가 도구로 보이므로 카드가 될 가능성이 크다 |
| `AskUserQuestion` | 도구 목록에 있다. 카드가 될 것이다 |

**❓ 셋이 남아 있는 동안 터미널을 지우면, 그 셋을 만난 사용자가 할 수 있는 일이 없다.** 그래서 남긴다 — 다만 **보조로**, 그리고 조건을 걸고.

| 무엇 | 언제 |
|---|---|
| 챗이 기본이 된다 | 8 절 4 단계 |
| 터미널은 세션 헤더의 "원시 터미널로 보기" 로만 열린다 | 4 단계 |
| **터미널 코드를 삭제한다** | ❓ 셋이 챗에서 되는 것이 확인된 뒤 — 8 절 7 단계 |

**두 화면 모델을 오래 끌지 않는 이유**를 적어둔다. `HeldSession` 이 지금 `TtyConnector` 를 쥐고 있고(`HeldSession.kt:17`), 챗 세션은 대화를 쥔다. 둘을 같은 홀더가 다루면 "이 세션이 어느 종류인가" 를 묻는 자리가 앱 곳곳에 생기고, 그 물음을 빠뜨린 자리가 곧 결함이다. **한 세션은 한 종류다** — 같은 대화를 터미널로도 챗으로도 동시에 열지 못한다(5.6 의 `already in use` 가 어차피 그것을 막는다). "원시 터미널로 보기" 는 **챗 세션을 끝내고 같은 대화를 터미널로 다시 여는 것**이다. 그 사실을 화면이 먼저 말한다(원칙 12).

---

## 8. 검토한 대안과 기각 사유

**가장 큰 대안은 "이 전환을 하지 않는 것" 이고, 그것은 2 절이 다뤘다.** 여기는 "챗으로 간다" 를 정한 뒤에 갈리는 길들이다.

### 8.1 Agent SDK 사이드카 (Node)

`@anthropic-ai/claude-agent-sdk` 를 쓰는 Node 프로세스를 앱이 띄우고, 앱은 그것과 이야기한다. 스트림 파싱과 권한 콜백을 SDK 가 이미 해준다.

**기각.** 셋이다.

1. **런타임이 하나 는다.** 지금 설치는 "앱을 깔면 엔진이 함께 온다" 한 걸음이다(`desktop/build.gradle.kts` 가 `kyu` 를 패키지에 담는다). Node 가 끼면 그것을 배포하거나 사용자에게 요구해야 하고, 윈도우 네이티브 목표에서 특히 비싸다.
2. **계층이 하나 는다.** 앱 → 사이드카 → `claude`. 사이드카가 죽었는지 `claude` 가 죽었는지를 가르는 일이 새로 생긴다.
3. **SDK 가 해주는 일이 우리에게 크지 않다.** 3 절이 잰 것이 그 증거다 — 줄 단위 JSON 파싱과 소켓 왕복 하나다. 그 대가로 런타임과 계층을 들이는 것은 맞지 않는다.

**다만 SDK 의 문서는 계속 본다.** 스트림의 이벤트 모양이 바뀌면 SDK 가 먼저 그것을 반영한다.

### 8.2 기존 터미널 유지 + 오버레이

터미널은 그대로 두고, 앱이 별도로 `claude` 의 전사 파일을 읽어 옆에 도구 카드를 그린다.

**기각.** 전사 파일은 `claude` 의 내부 저장 형식이다. app-owned 5.5.1 이 `--session-id` 를 "알아내는 것이 아니라 정해주는 것" 으로 만든 근거가 그대로 여기 걸린다 — *"알아내는 길이었다면 claude 의 내부 저장 형식에 기대게 되고, 그것은 계약이 아니라 구현 세부라 판이 바뀔 때 조용히 어긋난다."*

그리고 권한 승인은 여전히 터미널 안에서 답해야 한다 — 요구의 절반이 안 풀린다.

### 8.3 웹뷰 챗

챗 화면을 HTML 로 만들고 Compose 안에 웹뷰로 띄운다. 마크다운·코드 하이라이트·diff 를 성숙한 웹 라이브러리로 해결한다.

**기각.** Compose Multiplatform Desktop 에 표준 웹뷰가 없다. JCEF 를 들이면 패키지 크기가 수백 MB 늘고, 그것이 앱 배포의 성격을 바꾼다. 그리고 3.15 가 Compose 쪽에도 스트리밍까지 다루는 렌더러가 있다는 것을 확인했다 — 웹뷰가 풀어줄 문제가 남아 있지 않다.

### 8.4 `--output-format json` 으로 한 턴씩

`-p` 로 한 턴 돌리고 JSON 하나를 받는 방식. orchestration 3.4 가 이미 쟀고 위임이 그것을 쓴다.

**기각.** 턴마다 프로세스를 새로 띄우면 부팅 3 초(3.1)와 전체 컨텍스트 재전송이 매번 붙는다. 그리고 **스트리밍이 없다** — 사용자가 답을 다 쓸 때까지 빈 화면을 본다. 위임은 사람이 안 보고 있어서 그것이 괜찮았고, 챗은 사람이 보고 있다.

### 8.5 앱이 API 를 직접 부른다

`claude` 를 빼고 앱이 Anthropic API 를 직접 부른다.

**기각.** 그러면 `CLAUDE.md`·`.mcp.json`·훅·커스텀 에이전트·슬래시 명령이 전부 사라진다. **이 도구의 존재 이유가 "레포의 설정을 전부 활용한 채로" 였다**(workdir 1.3). 그것을 버리는 길은 검토 대상이 아니다.

---

## 9. 구현 단계

각 단계가 **PR 하나**이고, 각 단계 끝에 손으로 확인 가능한 상태가 된다.

**1·2 단계는 서로를 기다리지 않는다.** 테마·셸은 지금 화면 위에서 먼저 설 수 있고, 어댑터는 화면 없이 시험할 수 있다. 병렬로 간다.

| 단계 | 내용 | 완료 확인 |
|---|---|---|
| **1** (병렬 A) | **테마와 셸** — `KyuColorScheme`·`Typography`·`Shapes`, 다크 토글, 좌우 3 분할 셸. 대화 자리에는 지금의 터미널을 그대로 둔다 | 앱 전체가 새 색·타이포로 보이고 다크가 돈다. 레일에서 세션을 고르면 지금과 똑같이 터미널이 열린다. **화면 모델은 아직 안 바뀌었다** |
| **2** (병렬 B) | **어댑터** — `kyu session-command --chat`(5.2), `ChatSessionOpener`·`ProcessChatSession`, 이벤트 모델(5.3.2). 화면 없이 테스트로만 | `kyu session-command --json --chat` 이 3.1 의 argv 를 답한다. 어댑터 테스트가 **녹화한 이벤트 줄**을 넣어 sealed 이벤트로 옮기는 것을 확인한다(부록 A 의 출력을 고정 자료로 쓴다). 실제 `claude` 를 한 번 띄워 두 턴이 도는 것을 확인한다 |
| **3** | **말하고 듣는다** — `ChatSessionStateHolder`, 사용자 말풍선·모델 말풍선·마크다운(5.8)·스트리밍(6.6)·`TurnFooter`. 도구는 아직 일반 카드 | 레일에서 메인을 고르면 챗이 열리고, 치면 답이 글자 단위로 흐른다. 비용 배지가 뜬다. **도구를 쓰는 답도 오지만 카드는 밋밋하다** |
| **4** | **중단과 도구 카드** — `control_request`(3.8), `ToolCallCard` 갈래(6.3), 상세 패널. 챗이 기본이 되고 터미널은 "원시 터미널로 보기" 로 내려간다(7 절) | 긴 답 도중 중단 버튼을 누르면 턴만 끊기고 **다음 말을 걸면 답한다**. Bash·Edit·Read 카드가 갈라 보인다. 터미널은 메뉴 안에 있다 |
| **5** | **권한 브리지** — `kyu mcp ask`(5.2.2), 앱의 소켓 수신(5.4), `PermissionRequestCard`(6.4) | 승인이 필요한 일을 시키면 **앱 안에 카드가 뜬다**. 거절하면 파일이 안 생기고 모델이 이유를 안다. **인자를 고쳐 허용하면 고친 대로 돈다**. 소켓을 짧은 런타임 경로에 여는 것을 긴 워크디렉토리 이름으로 확인한다(3.7 의 107 바이트) |
| **6** | **위임과 서브에이전트** — `DelegationCard`(5.7), `SubagentCard`, `parentToolUseId` 중첩, `permission_denials` 표시 | 메인에게 레포 작업을 시키면 위임 카드가 뜨고 경과 시간이 흐른다. 서브에이전트를 띄우면 안쪽 대화가 접힌 채로 보인다 |
| **7** | **터미널 삭제** — 7 절의 ❓ 셋이 챗에서 되는 것을 확인한 뒤 | JediTerm·pty4j 의존과 `EmbeddedTerminalPane`·`HeldSessionTerminalWidgets`·`terminal/pty/` 가 사라진다. **패키지 크기가 준다** |

**1·2 를 병렬로 두는 이유**: 둘이 겹치는 파일이 없다. 1 은 `KyuchestrationDesktopScreen.kt` 와 새 테마 파일을, 2 는 `internal/cli/` 와 새 `terminal/chat/` 을 만진다. 그리고 1 은 눈으로만 판정되고 2 는 테스트로만 판정된다 — 리뷰의 성격도 갈린다.

**5 단계가 3·4 뒤인 이유**: 승인 카드를 그리려면 대화가 먼저 있어야 한다. 그리고 브리지는 이 설계에서 새로 만드는 것이 가장 많은 자리라(소켓·새 하위 명령·양쪽 직렬화), 앞의 것들이 서 있어야 실패가 어디서 났는지 갈린다.

**7 단계에 조건이 붙어 있다.** 조건이 안 차면 그 PR 은 열리지 않는다 — 7 절이 그 조건이다.

---

## 10. 열린 질문

구현을 막지는 않지만 해당 단계에서 정해야 하는 것들이다.

1. **`/login`·`/mcp` 승인·플랜 모드가 챗에서 되는가** — 7 절의 ❓ 셋. **4 단계에서 잰다.** 이 답이 7 단계의 존재 여부를 정한다. 인증은 실제 계정이 걸려 있어 프로브로 재기 어렵고, 앱에서 직접 해보는 것이 유일한 길이다.

2. **컨텍스트가 찰 때 무엇이 오는가** — 3.12 가 못 쟀다. 자동 압축이 이벤트로 오는지, 온다면 어느 `type` 인지. 안 오면 챗은 대화가 갑자기 짧아진 것을 설명하지 못한다. `/context` 를 주기적으로 부르는 길도 있지만 그것은 턴을 하나 쓴다. **3 단계에서 긴 대화를 실제로 만들어 본다.**

3. **전사를 파일로 남길 것인가** — 5.5. 남기면 앱을 다시 열어도 화면이 이어지고, `claude` 의 전사와 우리 전사가 둘이 된다. 후보: (가) 남기지 않는다(v1) (나) `.coord/transcripts/<대화 ID>.jsonl` 에 받은 이벤트를 그대로 적는다 (다) `claude` 의 전사를 읽는다 — **(다)는 8.2 의 이유로 기각.** (나)를 언제 열지는 사용자가 "앱을 껐다 켜면 대화가 안 보인다" 를 실제로 불편해할 때 정한다.

4. **"항상 허용" 을 어디에 적을 것인가** — 6.4. 앱이 사용자 `settings.json` 을 고치는 것은 큰 결정이다. 후보: (가) 두지 않는다(v1) (나) 워크디렉토리 `.coord/` 에 우리 규칙을 적고 브리지가 그것을 먼저 본다 (다) 사용자 설정을 고친다. **(나)가 유력하다** — 원칙 2("레포 안에는 아무것도 넣지 않는다")를 지키면서 우리 것을 우리 자리에 둔다. **5 단계에서 정한다.**

5. **위임 안쪽의 승인을 사용자에게 올릴 것인가** — 5.4.2. 지금은 안 올린다. 올리면 사용자가 안 보는 레포의 물음에 답하게 되고, 안 올리면 위임은 계속 `auto` 로 돈다. **6 단계 이후.**

6. **위임 진행을 앱 소켓으로 밀 것인가** — 5.7 의 두 번째 걸음. 채널이 이미 있으므로 비용이 작지만, 첫 사용처가 선 뒤에 정한다. **6 단계 이후.**

7. **`kyu mcp ask` 라는 이름** — 5.2.2. `approve` 와 가깝다. 사용법 문구에만 나오는 이름이라 짧은 쪽을 먼저 쓰지만, 2 단계에서 문구를 써 보고 헷갈리면 그때 바꾼다.

8. **큐 깊이를 정확히 그릴 것인가** — 3.11. `still_queued` 와 `queued_turn_count` 를 비어 있지 않은 상태로 재지 못했다. 앱이 자기가 보낸 수와 받은 `result` 수를 세는 것으로 v1 은 충분하다고 보지만, 그 셈이 어긋나는 경우가 있는지 **3 단계에서 본다.**

9. **한글 폰트를 무엇으로 고정할 것인가** — 6.1. 지금은 시스템 기본이라 리눅스와 윈도우가 다르게 보인다. 폰트를 패키지에 담으면 크기가 늘고, 담지 않으면 화면이 머신마다 다르다. **목업 확정 시.**

10. **`init.permissionMode` 를 화면에 보일 것인가** — 3.6 이 플래그 이름(`manual`)과 보고 이름(`default`)이 다른 것을 쟀다. 그대로 보이면 사용자가 준 적 없는 낱말이 뜬다. 우리 낱말로 옮겨 보이거나 아예 안 보이거나. **3 단계.**

---

## 부록 A. 실측 원출력 요약

**환경**: 2026-09-04, `claude` 2.1.259, Linux(WSL2). 모델은 세션 기본값(`claude-fable-5-1`, 일부 프로브는 `claude-opus-5[1m]`). 대부분 `--setting-sources ""` 로 사용자 설정을 걷어냈다.

### A.1 두 턴이 한 프로세스에서 (3.1)

```
t=1.42  system/hook_started      SessionStart:startup
t=1.42  system/hook_response
t=3.53  system/init              session_id=84a72c08-…  permissionMode=auto
t=6.11  assistant                text: "KYUCHAT-2026"
t=6.12  rate_limit_event
t=6.12  result/success           num_turns=1  total_cost_usd=0.2829…
t=6.71  system/init              session_id=84a72c08-…      ← 같은 ID, init 이 다시 온다
t=9.04  assistant                text: "KYUCHAT-2026"
t=9.12  result/success
```

### A.2 `system/init` 의 키 (23 개)

```
agents · analytics_disabled · apiKeySource · capabilities · claude_code_version ·
cwd · fast_mode_disabled_reason · fast_mode_state · mcp_servers · memory_paths ·
messaging_socket_path · model · output_style · permissionMode · plugins ·
product_feedback_disabled · session_id · skills · slash_commands · subtype ·
terminal_slash_commands · tools · type
```

`capabilities` 의 값: `["interrupt_receipt_v1","interrupt_cancel_queued_v1","msg_lifecycle_v1"]`

### A.3 도구 호출과 결과 (3.4)

```json
{"type":"tool_use","id":"toolu_01R5ZERot1B5tdJ6XAngsu7V","name":"Read",
 "input":{"file_path":"…/README.txt"},"caller":{"type":"direct"}}
```

```json
{"type":"user",
 "message":{"role":"user","content":[
   {"tool_use_id":"toolu_01R5ZERot1B5tdJ6XAngsu7V","type":"tool_result",
    "content":"1\thello from fake repo\n2\t"}]},
 "tool_use_result":{"type":"text","file":{"filePath":"…/README.txt",
   "content":"hello from fake repo\n","numLines":2,"startLine":1,"totalLines":2}}}
```

### A.4 부분 스트리밍의 순서 (3.3)

```
stream_event  message_start
stream_event  content_block_start   {"type":"text","text":""}
stream_event  content_block_delta   {"type":"text_delta","text":"README"}
assistant                            text 블록 완성본
stream_event  content_block_stop
stream_event  content_block_start   {"type":"tool_use","id":"toolu_…","name":"Read","input":{}}
stream_event  content_block_delta   {"type":"input_json_delta","partial_json":"{\"file_path\": \"/tmp/…"}
stream_event  content_block_delta   {"type":"input_json_delta","partial_json":"\"}"}
assistant                            tool_use 블록 완성본
stream_event  content_block_stop
user                                 tool_result
```

### A.5 권한 요청과 답 (3.5)

프로브 서버가 받은 것:

```json
{"method":"tools/call","id":2,
 "params":{"name":"approve",
  "arguments":{"tool_name":"Write",
   "input":{"file_path":"…/probe-written.txt","content":"WRITTEN\n"},
   "tool_use_id":"toolu_01EK4pNYexJLbkuiiosevhXq"},
  "_meta":{"claudecode/toolUseId":"toolu_01EK4pNYexJLbkuiiosevhXq","progressToken":2}}}
```

`deny` 로 답했을 때 스트림에 온 것:

```json
{"type":"tool_result","content":"사람이 거절했습니다 (프로브)","is_error":true,
 "tool_use_id":"toolu_01EK4pNYexJLbkuiiosevhXq"}
```

`allow` + 바꾼 `updatedInput` 으로 답했을 때 — 모델은 `echo ORIGINAL > upd.txt` 를 요청했다:

```json
{"tool_use_id":"toolu_01BVshLj5mQ6H7AfGr792dum","type":"tool_result",
 "content":"MODIFIED-BY-APP","is_error":false}
```

`upd.txt` 는 만들어지지 않았다.

### A.6 권한 모드 여섯 (3.6)

| `--permission-mode` | `init` 보고 | 시킨 일 | 승인 도구 호출 | 결과 |
|---|---|---|---|---|
| `manual` | `default` | Write | 1 | 거절됨, 파일 없음 |
| `manual` | `default` | Bash `echo` | 0 | 실행됨 |
| `acceptEdits` | `acceptEdits` | Write | 0 | 실행됨 |
| `auto` | `auto` | Write | 0 | 실행됨 |
| `bypassPermissions` | `bypassPermissions` | Write | 0 | 실행됨 |
| `dontAsk` | `dontAsk` | Write | 0 | 자동 거절, `permission_denials` 에 1 건 |

### A.7 승인 도구가 모델에게서 사라진다 (3.5)

| 실행 | `--permission-prompt-tool` | 모델에게 보인 MCP 도구 |
|---|---|---|
| 서버 둘 | 없음 | `mcp__kyuprobe__approve`, `mcp__kyuslow__run_in_repo_probe` |
| 서버 둘 | `mcp__kyuprobe__approve` | `mcp__kyuslow__run_in_repo_probe` |
| 서버 하나 | `mcp__kyuprobe__approve` | (없음) |

### A.8 소켓 왕복 (3.7)

```
{"app_listening":"/run/user/1000/kyu-approval-probe.sock","mode":"allow","answer_delay_s":12.0}
{"app_received":{"ask":{"tool_name":"Write","input":{…},"tool_use_id":"toolu_01R5VjxJZW7XouXG3tSHUrvA"}}}
{"socket_roundtrip_ms":11988.4,"answer":{"decision":"allow"}}
```

150 초짜리:

```
{"socket_roundtrip_ms":149986.5,"answer":{"decision":"allow"}}
```

둘 다 파일이 만들어졌다. 짧은 경로로 옮기기 전의 실패:

```
OSError: AF_UNIX path too long
```

### A.9 중단 (3.8)

보낸 것:

```json
{"type":"control_request","request_id":"int_b8acf0f7","request":{"subtype":"interrupt"}}
```

받은 것:

```
control_response  {"subtype":"success","request_id":"int_b8acf0f7","response":{"still_queued":[]}}
user              text: "[Request interrupted by user]"
result/error_during_execution   terminal_reason=aborted_streaming  result=null
system/task_updated             {"status":"killed"}          ← 백그라운드 도구가 있었을 때
```

그다음 같은 프로세스에 물었을 때: `"헥사고날"`

`SIGINT` 은 같은 턴 결과를 내되 프로세스가 함께 끝났다.

### A.10 resume (3.9)

```
--session-id b84cfe83-…  →  "알겠습니다."          (암호 CHATRESUME-88 을 알려줌)
--resume     b84cfe83-…  →  "CHATRESUME-88"        (새 프로세스)
--resume     00000000-…  →  result/error_during_execution, 종료 코드 1
                            stderr: No conversation found with session ID: 00000000-…
```

### A.11 서브에이전트 (3.10)

```
assistant                  tool_use: Agent{subagent_type:"general-purpose", …}
system/task_started        task_id=a0130b1ed866786d2  spawn_depth=1  task_type=local_agent
user       (parent=toolu_015ezC9Koz…)   프롬프트
assistant  (parent=toolu_015ezC9Koz…)   "I'll read the file."
system/task_progress       description="Reading README.txt"  last_tool_name="Read"
                           usage={total_tokens:9942, tool_uses:1, duration_ms:4398}
assistant  (parent=toolu_015ezC9Koz…)   tool_use: Read
user       (parent=toolu_015ezC9Koz…)   tool_result
system/task_updated        {"status":"completed"}
system/task_notification   output_file=…/tasks/a0130b1ed866786d2
user                       tool_result   (바깥 Agent 호출의 답)
result/success             subagent_stats={spawned:1, completed:1, max_depth:1, by_type:{"general-purpose":1}}
```

MCP 도구 쪽에서는 `notifications/progress` 를 3 회 보냈는데 스트림의 `progress` 관련 이벤트는 **0 개**였다.

### A.12 비용과 한도 (3.12)

```json
{"type":"result","subtype":"success","total_cost_usd":0.28292174999999997,
 "usage":{"input_tokens":2,"cache_creation_input_tokens":13990,
          "cache_read_input_tokens":10007,"output_tokens":12},
 "modelUsage":{"claude-fable-5-1":{"contextWindow":1000000,"maxOutputTokens":64000,
                                   "costUSD":0.28292174999999997,"provider":"firstParty"}},
 "duration_ms":2679,"duration_api_ms":2475,"ttft_ms":2659,
 "num_turns":1,"stop_reason":"end_turn","terminal_reason":"completed",
 "permission_denials":[],"api_error_status":null}
```

```json
{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","rateLimitType":"five_hour",
 "unifiedWindows":{"five_hour":{"utilization":0.43,"resetsAt":1788513600},
                   "seven_day":{"utilization":0.04,"resetsAt":1789074000}}}}
```

### A.13 슬래시 명령 (3.13)

```
/probefp   →  assistant: "SLASHFP-9090"                        num_turns=1
/context   →  assistant: "## Context Usage\n\n**Model:** …"    num_turns=0
```

### A.14 큐잉 (3.11)

```
t=0.00  보냄: "1 부터 40 까지 세면서 …"      (긴 답)
t=1.50  보냄: "[둘째] 사과의 색은?"
t=1.80  보냄: "[셋째] 바나나의 색은?"
        받음: result ×2   —  둘째 답: "빨강\n노랑"
```

### A.15 서버 이름의 하이픈 (5.2.2)

```
mcpServers: {"kyu-ask": {…}}
  → init.mcp_servers  : ["kyu-ask", …]
  → init.tools        : ["mcp__kyu-ask__approve"]
```

같은 자리의 다른 서버:

```
"plugin:context7:context7"  →  mcp__plugin_context7_context7__query-docs
```

---

## 부록 B. 실측 재현 방법

전부 격리된 스크래치 디렉토리에서 돌았다. 실제 레포도 실제 토큰도 쓰지 않았다.

**재료**

| 파일 | 하는 일 |
|---|---|
| `fakerepo/CLAUDE.md` | 지문 `KYUCHAT-2026` 하나 |
| `fakerepo/.claude/commands/probefp.md` | 지문 `SLASHFP-9090` 을 답하는 슬래시 명령 |
| `drive.py` | 양방향 구동기 — 프로세스를 띄우고, 사용자 메시지를 순서대로 보내고, 이벤트를 전부 기록한다. `--interrupt-after` 로 중단 지점을 정한다 |
| `permserver.py` | 승인 도구 하나짜리 stdio MCP 서버. 받은 요청을 로그에 적고, 정해진 답(`allow`·`allow_modified`·`deny`)을 돌려준다. `KYU_PROBE_SOCKET` 이 있으면 부모에게 물어서 답한다 |
| `slowserver.py` | 6 초 걸리는 도구 하나. 도는 동안 `notifications/progress` 를 3 회 보낸다 |
| `appsocket.py` | 앱 역할. 유닉스 소켓에서 물음을 받아 정해진 지연 뒤에 답한다 |

**판정 근거**

| 무엇 | 무엇으로 갈랐나 |
|---|---|
| 권한 허용·거절 | **파일이 생겼는지** (`probe-written.txt` · `sock.txt` · `socklong.txt`) |
| 인자 수정 | 도구 결과가 `MODIFIED-BY-APP` 이고 `upd.txt` 가 없다 |
| 승인 도구 호출 여부 | 서버가 남긴 로그의 `PERMISSION_REQUEST` 줄 수 |
| MCP 진행 알림 | 서버는 보냈다고 로그에 적었고, 스트림에는 0 개 |
| 중단 뒤 생존 | 같은 프로세스가 다음 질문에 답했는가 |
| resume | 앞 턴에서만 알려준 값을 새 프로세스가 답했는가 |
| 슬래시 명령 | 레포에만 있는 지문을 답했는가 |
| 모델의 도구 목록 | `system/init` 의 `tools` 배열 |

**환경 격리**: 대부분의 프로브에 `--setting-sources ""` 를 주어 사용자·프로젝트·로컬 설정을 걷어냈다. 그러지 않으면 이 머신의 `defaultMode: auto` 때문에 권한 관문이 열리지 않고, 그것이 이 판의 성질인지 이 머신의 설정인지 갈리지 않는다.

---

## 부록 C. 확인된 사실

3 절에서 **재서** 확정한 것만 적는다. 추정과 문서 조사는 따로 표시했다.

1. `claude -p --input-format stream-json --output-format stream-json` 은 **한 프로세스가 여러 턴을 산다.** `session_id` 가 같고 대화가 이어진다.
2. `system/init` 은 **턴마다 다시 온다.** 세션의 시작 신호가 아니다.
3. `assistant` 이벤트는 **콘텐츠 블록 단위**다. 메시지 단위가 아니다.
4. `--include-partial-messages` 의 `content_block_start` 는 **도구 이름을 인자보다 먼저** 준다.
5. `user` 이벤트의 `tool_use_result` 곁가지는 **도구마다 다른 모양의 구조화된 결과**를 담는다.
6. `--permission-prompt-tool` 의 요청 인자는 `{tool_name, input, tool_use_id}` 이고, 답은 `{"behavior":"allow","updatedInput":…}` 또는 `{"behavior":"deny","message":…}` 다.
7. **`updatedInput` 으로 인자를 바꾸면 바꾼 대로 실행된다.**
8. **`--permission-prompt-tool` 로 지정된 도구는 모델의 도구 목록에서 사라진다.**
9. `--permission-mode manual` 은 `init` 에서 **`default`** 로 보고된다.
10. `default` 에서도 **모든 도구 호출이 관문을 타지는 않는다** — `echo` 는 그냥 돈다.
11. `dontAsk` 는 **묻지 않고 거절**하고 `result.permission_denials` 에 남긴다.
12. `acceptEdits`·`auto`·`bypassPermissions` 에서는 승인 도구가 **한 번도 불리지 않는다.**
13. MCP 서버가 부모에게 **유닉스 소켓으로 물어 150 초 뒤에 답해도** 승인이 통한다.
14. `AF_UNIX` 경로는 **107 바이트가 한계**다. 긴 스크래치 경로에서는 소켓이 열리지 않았다.
15. `control_request` 의 `{"subtype":"interrupt"}` 는 **턴만 끊고 프로세스와 대화를 살려둔다.** 중단 뒤 같은 프로세스가 앞 맥락을 기억한 채 답했다.
16. `SIGINT` 은 **프로세스를 함께 끝낸다.**
17. `--session-id` 와 `--resume` 이 이 모드에서 **그대로 돈다.** 없는 대화를 resume 하면 stderr 한 줄 + `result` 이벤트 + 종료 코드 1 이다.
18. MCP 의 `notifications/progress` 는 **출력 스트림에 나타나지 않는다.**
19. 서브에이전트는 `system/task_started`·`task_progress`·`task_updated`·`task_notification` 으로 **진행을 준다.** `--forward-subagent-text` 를 켜면 안쪽 대화가 `parent_tool_use_id` 가 채워진 채로 온다.
20. 턴이 도는 중에 보낸 메시지들은 **큐에 쌓였다가 한 턴으로 합쳐진다.**
21. 슬래시 명령이 **이 모드에서 돈다** — 레포의 커스텀 명령과 `/context` 같은 내장 로컬 명령 둘 다. `/context` 는 모델을 부르지 않고(`num_turns: 0`) 마크다운을 답한다.
22. `--replay-user-messages` 는 앱이 보낸 사용자 메시지를 `user` 이벤트로 되돌려준다.
23. `--mcp-config` 를 **두 번 줄 수 있고** 두 서버가 다 붙는다.
24. MCP 서버 이름의 **하이픈은 도구 이름에 그대로 남는다** — `kyu-ask` → `mcp__kyu-ask__approve`. 콜론은 밑줄로 바뀐다(`plugin:context7:context7` → `plugin_context7_context7`).
25. `result` 는 비용·토큰·컨텍스트 창 크기·소요 시간·중단 사유·권한 거절 목록·서브에이전트 통계를 담는다. `rate_limit_event` 는 5 시간·7 일 창의 사용률을 준다.

**문서 조사로 확인한 것** (코드 실행 없음, 3.15)

26. `com.mikepenz:multiplatform-markdown-renderer` 0.45.0 (2026-08-28)이 Compose Desktop JVM·Material 3 모듈·코드 하이라이트 모듈·스트리밍 전용 API 를 갖는다. Apache-2.0.
27. 그 하이라이터 `dev.snipme:highlights:1.1.0` 은 언어 17 개를 enum 이름 완전일치로 매칭한다 — `json`·`yaml`·`sql`·`diff` 가 없고 `bash`·`js`·`py` 같은 별칭이 매칭되지 않는다.

**아직 안 쟀거나 못 잰 것**

28. `/login`·`/mcp` 승인·플랜 모드가 챗 모드에서 어떻게 되는지 (10 절 1 번).
29. 컨텍스트가 찰 때 압축이 이벤트로 오는지 (10 절 2 번).
30. `still_queued`·`queued_turn_count` 가 비어 있지 않은 경우 (10 절 8 번).
31. `--mcp-config` 가 **대화형**에서도 승인 관문을 안 타는지 — orchestration 3.8 이 `-p` 로만 쟀고, 챗 모드도 `-p` 라 이 설계에서는 문제가 되지 않는다.

---

## 부록 D. 용어

| 낱말 | 뜻 |
|---|---|
| **챗 모드** | 앱이 `claude` 를 `--input-format stream-json --output-format stream-json` 으로 띄우고 이벤트에서 화면을 직접 그리는 것 |
| **터미널 모드** | 지금까지의 것 — PTY 를 열고 `claude` 가 그린 격자를 그대로 보이는 것 |
| **전사** | 챗 화면에 쌓인 대화 전체. `claude` 가 자기 디렉토리에 남기는 전사와 다른 것이다 |
| **턴** | 사용자 메시지 하나에서 `result` 하나까지 |
| **블록** | 모델의 한 답 안의 조각 — 텍스트·사고·도구 호출 |
| **승인 브리지** | `kyu mcp ask` ↔ 앱 소켓. 도구 호출 승인 물음을 앱까지 나르는 통로 |
| **관문** | 승인이 필요한 자리. 이 문서에서는 도구 호출 승인을, orchestration 5.6 에서는 레포 설정 승인을 가리킨다 |
| **셸** | 창 안의 골격 — 레일·대화·상세 세 자리 |
