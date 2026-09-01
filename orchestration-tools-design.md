# 메인이 레포 세션을 부린다 — 오케스트레이션 도구 설계

> 사용자가 메인과 대화하기만 하면, 필요한 레포에서 세션이 저절로 떠서 **그 레포의 설정을 전부 로드한 채로** 고친다. 엔진이 MCP 서버가 되어 메인 세션에 위임 도구를 쥐여주는 것으로 그것을 한다.

- 작성일: 2026-09-01
- 상태: **설계 확정 전 — 실측 완료, 사용자 승인 대기.** 구현 미착수
- 선행 문서: [workdir-orchestrator-design.md](workdir-orchestrator-design.md) — 특히 1.3(근본 제약), 3.1(서브에이전트 대안), 원칙 2·3·6, **11절 열린 질문 1**
- 선행 문서: [app-owned-sessions-design.md](app-owned-sessions-design.md) — 특히 5.2(`kyu session-command`), 5.4(두 곳에서 오는 사실), 5.5(대화 이어가기), 원칙 11
- 전제: **CLI 세션 기능이 은퇴한 뒤의 엔진.** `kyu` 는 순수 엔진이고 세션 백엔드를 갖지 않는다. 이 전환은 별도 PR 로 진행 중이며, 이 설계는 그 결과 위에 선다
- 이 문서가 닫는 것: **선행 문서 11절 1번** — *"헤드리스 세션 실행 … `.mcp.json` 승인이 어떻게 처리되는지, 파일 수정 권한을 어떻게 부여하는지 확인되지 않았다"*. 2026-09-01 에 쟀고 3절이 그 답이다

---

## 1. 배경 — 무엇이 아직 불편한가

### 1.1 사용자가 말한 것

> "수동으로 카드를 눌러 레포 세션을 띄우는 게 아니라, 그냥 메인과 대화하면 필요에 따라 알아서 클론 레포에 세션이 뜨면서 **해당 레포의 MCP 설정 등을 다 활용한 채로** 수정이 되면 좋겠어."

이 문장에 요구가 셋 들어 있다.

| 요구 | 지금 |
|---|---|
| **사람이 카드를 고르지 않는다** — 무엇을 어느 레포에서 할지는 대화에서 정해진다 | 사람이 카드를 누른다 |
| **세션이 저절로 뜬다** | 사람이 띄운다 |
| **레포의 설정이 전부 살아 있다** | 카드를 눌러 뜬 세션에서는 이미 성립한다 |

**세 번째는 이미 되고 있다.** 앱이 레포 cwd 에서 `claude` 를 직접 띄우므로(app-owned 5.3) 그 세션은 레포의 설정을 그대로 받는다. 남은 것은 앞의 둘이고, 그 둘의 공통점은 **사람이 손으로 하는 일**이라는 것이다.

### 1.2 근본 제약은 그대로다

선행 문서 1.3 이 이 도구의 존재 이유로 적어둔 표는 이번에도 그대로 성립한다.

> **설정은 오직 세션을 시작한 디렉토리에서만 온다.**

그래서 "메인이 레포를 고친다" 는 길은 없다. 메인의 cwd 는 워크디렉토리이고, `--add-dir` 은 파일 접근만 준다. 메인이 `proj-a/src/Foo.java` 를 직접 고칠 수는 있지만 그때 `proj-a` 의 MCP 서버도 지침도 훅도 붙지 않는다 — **사용자 요구의 핵심인 "MCP 설정 등을 다 활용한 채로" 가 정확히 성립하지 않는 자리다.**

그러므로 답은 하나뿐이다. **레포 cwd 에서 도는 진짜 프로세스가 그 일을 해야 한다.** 이 설계가 정하는 것은 "누가 그것을 띄우는가" 이고, 답은 "사람 대신 메인 세션의 Claude 가" 다.

### 1.3 선행 문서 3.1 이 남겨둔 자리

선행 문서 3.1 이 "워크디렉토리 단일 세션 + 서브에이전트" 를 검토하고 세 가지 이유로 기각했다. 그중 둘이 이 설계에 그대로 이어진다.

| 3.1 의 기각 사유 | 이 설계에서 |
|---|---|
| (1) 레포가 늘 때마다 에이전트 정의 파일을 만들어야 한다 | **사라진다.** 위임은 도구 호출 한 번이고 정의 파일이 없다. 레포는 스캔으로 발견된다(선행 문서 5.3) |
| (2) **레포의 나머지 설정은 여전히 안 따라온다** — MCP 를 inline 으로 해결해도 `CLAUDE.md`·커스텀 에이전트·훅은 로드되지 않는다 | **넘는다.** 서브에이전트가 아니라 **레포 cwd 의 진짜 프로세스**라서 넷 전부가 로드된다. 3.1 이 이 설계의 근거다 |
| (3) 위임마다 MCP 서버가 부팅된다 | **남는다.** 다만 크기를 이제 안다 — 3.6 |

**(2)가 이 설계의 뼈대다.** 3.1 은 "MCP 는 inline 으로 되지만 나머지 셋은 안 된다" 에서 멈췄다. 레포 cwd 에서 프로세스를 띄우면 넷이 한꺼번에 해결된다 — 그것이 애초에 이 도구가 각 레포에서 세션을 띄우는 이유였고(선행 문서 1.3), 위임은 그 세션을 사람 대신 띄우는 일일 뿐이다.

**그런데 그 넷이 헤드리스에서도 정말 사는지는 아무도 재본 적이 없다.** 선행 문서 11절 1번이 그것을 열린 질문으로 남겼고, v1 범위에서 "세션에 작업 자동 주입" 을 제외한 사유가 바로 그것이었다(선행 문서 7절). 이 문서는 먼저 그것을 잰다.

---

## 2. 핵심 문제 — 위임이 진짜 세션과 같아야 한다

선행 문서 2절이 *"프로세스는 격리, 지식은 공유"* 였다. 이번 문서의 같은 자리는 이렇다.

**위임된 실행이 사람이 손으로 띄운 세션과 같지 않으면, 이 기능은 편의가 아니라 함정이다.** 다르다면 사용자는 "카드를 눌러 하면 되는데 위임하면 안 되는 일" 을 하나씩 발견하게 되고, 그때마다 왜 다른지를 알아내야 한다.

같아야 하는 것이 넷이다 — 선행 문서 1.3 의 표 그대로다.

| 항목 | 위치 |
|---|---|
| MCP 서버 설정 | `<repo>/.mcp.json` |
| 프로젝트 지침 | `<repo>/CLAUDE.md` |
| 커스텀 에이전트 | `<repo>/.claude/agents/` |
| 프로젝트 설정·훅 | `<repo>/.claude/settings.json` |

그리고 다를 수밖에 없는 것이 둘이다.

| 다른 것 | 왜 |
|---|---|
| **사람이 없다** | 승인 프롬프트에 답할 사람이 없다. 권한을 미리 정해야 한다 |
| **화면이 없다** | 진행을 볼 수 없다. 결과를 문서로 받아야 한다 |

3절이 앞의 넷을 재고, 뒤의 둘도 함께 잰다.

---

## 3. 실측 — 선행 문서 11절 1번을 닫는다

**2026-09-01, `claude` 2.1.252, WSL2(Linux).** 격리된 스크래치 디렉토리에 가짜 레포를 만들어 쟀다. 재료는 전부 가짜다 — 지문 문구를 담은 `CLAUDE.md`, 부팅 사실을 로그에 남기고 지문 하나를 답하는 초소형 stdio MCP 서버, `SessionStart` 훅 하나, 측정용 서브에이전트 하나. 실제 레포도 실제 토큰도 쓰지 않았다. 재현 방법은 부록 B 다.

**판정 방식**: 모델의 말을 믿지 않는다. MCP 는 **서버 프로세스가 남긴 부팅 로그**로, 훅은 **훅이 쓴 파일**로, 에이전트는 **종료 코드**로 갈랐다. 모델이 지어낼 수 없는 것만 근거로 쓴다.

### 3.1 레포 설정 넷이 헤드리스에서 사는가 — 전부 산다

레포 cwd 에서 `claude -p` 를 실행했을 때다.

| 항목 | 결과 | 무엇으로 갈랐나 |
|---|---|---|
| `CLAUDE.md` | ✅ 로드된다 | 지문 `KYUFP-7431` 을 답했다 |
| `.mcp.json` | ✅ 붙는다 | 프로브 서버가 **부팅되고** `initialize`·`tools/list` 를 받았다 |
| `.claude/settings.json` (훅) | ✅ 로드된다 | `SessionStart` 훅이 로그 파일에 한 줄 적었다 |
| `.claude/agents/` | ✅ 발견된다 | 레포 cwd 에서 `--agent probe-agent` 종료 코드 0. **같은 명령이 레포 밖에서는 종료 코드 1** 과 `--agent 'probe-agent' not found` |

**선행 문서 1.3 의 표가 헤드리스에서 통째로 뒤집힌다.** 그 표는 "워크디렉토리 세션에서" 넷이 전부 ❌ 라는 것이었고, 여기서는 레포 cwd 라서 넷이 전부 ✅ 다. 같은 제약의 앞뒷면이다 — 설정은 세션을 시작한 디렉토리에서만 오고, 위임은 그 디렉토리에서 시작한다.

**에이전트 항목은 갈라 적어둔다.** 종료 코드는 발견됐다고 말했지만, 그 에이전트가 답한 내용은 `AGENTFP-5150` 이 아니라 `KYUFP-7431` 이었다 — 에이전트 지문과 `CLAUDE.md` 지문이 서로 다른 답을 지시하도록 측정 재료를 잘못 만들었고, 모델이 `CLAUDE.md` 를 따랐다. **발견 여부는 종료 코드로 확정됐고, 우선순위는 재지 못했다.** 재료의 결함이지 발견의 결함이 아니다.

### 3.2 `.mcp.json` 승인 — 헤드리스는 관문을 타지 않는다

**이것이 선행 문서 11절 1번이 물은 바로 그 질문이고, 답이 예상과 다르다.**

승인 이력이 전혀 없는 새 프로젝트에서 쟀다(`~/.claude.json` 에 이 경로가 없는 것을 먼저 확인했다).

| 잰 것 | 결과 |
|---|---|
| `claude mcp list` | `kyu-probe: … - ⏸ Pending approval (run \`claude\` to approve)` — **연결하지 않고, 서버가 부팅되지도 않는다** |
| 같은 상태에서 `claude -p` | **붙는다.** 프로브 서버가 부팅되고 `tools/list` 를 받는다 |
| `-p` 를 돌린 **뒤** 다시 `claude mcp list` | 여전히 `⏸ Pending approval` — **`-p` 실행은 승인을 저장하지 않는다** |

**즉 승인 관문은 대화형에만 있다.** `claude --help` 가 `-p` 항목에 적어둔 것과 같은 계열이다 — *"The workspace trust dialog is skipped when Claude is run in non-interactive mode."*

**설계에 미치는 영향 (좋은 쪽)**: 위임은 아무 준비 없이 레포의 `.mcp.json` 을 받는다. 엔진이 승인을 미리 심을 필요가 없고, 승인 상태를 관리할 필요도 없다. **설계에서 "승인 처리" 절이 통째로 필요 없어졌다.**

**설계에 미치는 영향 (나쁜 쪽)**: 선행 문서 1.3 이 그 관문에 대해 적어둔 문장이 여기서 되돌아온다.

> 이 제약은 버그가 아니라 보안 설계다. `.mcp.json` 은 임의 명령을 실행하는 파일이므로(`command`, `args` 필드), 추가 디렉토리의 것을 자동 로드하면 레포를 클론하는 것만으로 코드가 실행된다.

**위임은 그 관문이 없는 길이다.** `kyu clone` 으로 레포를 받고 메인이 그 레포에 위임하는 순간, 그 레포의 `.mcp.json` 에 적힌 명령이 사람에게 한 번도 보이지 않은 채 돈다. 5.6 이 이것을 다룬다.

### 3.3 권한 — 무엇을 켜야 위임이 일을 하는가

같은 프롬프트(MCP 도구 하나 호출 + 파일 하나 수정)를 플래그만 바꿔 돌렸다.

| 플래그 | MCP 도구 호출 | 파일 수정 |
|---|---|---|
| (없음 — 기본) | ❌ | ❌ |
| `--permission-mode acceptEdits` | ❌ | ✅ |
| `--permission-mode dontAsk` | ❌ | ❌ |
| `--permission-mode auto` | ✅ | ✅ |
| `--allowedTools "mcp__kyu-probe__repo_fingerprint Edit Write"` | ✅ | ✅ |
| `--dangerously-skip-permissions` | ✅ | ✅ |

**`acceptEdits` 가 답이 아니라는 것이 이 표의 요점이다.** 이름만 보면 "편집을 허용" 이라 위임에 알맞아 보이는데, **MCP 도구 호출을 막는다.** 그것을 고르면 사용자 요구의 *"해당 레포의 MCP 설정 등을 다 활용한 채로"* 가 성립하지 않는다 — 서버는 붙어 있는데 부를 수가 없다.

**막힌 실행이 실패로 끝나지 않는다.** 이것이 이 절에서 가장 중요한 사실이다.

```
exit=0
subtype = 'success'
is_error = False
permission_denials = [{"tool_name": "mcp__kyu-probe__repo_fingerprint", …},
                      {"tool_name": "Edit", …}]
result = 'repo_fingerprint 도구와 target.txt 파일 편집에 권한이 필요합니다. 사용자님의 승인을 기다리고 있습니다.'
```

**종료 코드도 0 이고 `subtype` 도 `success` 다.** 아무것도 하지 못한 위임이 성공한 위임과 종료 코드로 구별되지 않는다. `permission_denials` 배열을 읽는 것 말고는 아는 길이 없다 — 5.4 가 이것을 계약으로 고정한다.

**`--allowedTools` 로 이름을 나열하는 길도 된다.** 다만 그러려면 엔진이 그 레포의 MCP 서버가 무슨 도구를 갖고 있는지를 미리 알아야 하고, 그것은 서버를 띄워봐야 아는 것이다. 위임할 때마다 서버를 두 번 띄우게 된다. **쓰지 않는다.**

### 3.4 `--output-format json` — 엔진이 읽을 것이 다 있다

최상위 키가 23 개다. 엔진이 쓸 것만 적는다.

| 키 | 쓰임 |
|---|---|
| `result` | 위임의 답 본문 |
| `is_error` · `subtype` | 성공/실패. **권한 거절은 여기 안 잡힌다**(3.3) |
| `permission_denials` | 거절된 도구 호출 목록. **반드시 읽어야 한다** |
| `session_id` | 이 위임의 대화 ID |
| `total_cost_usd` | 위임 한 번의 비용 |
| `duration_ms` · `duration_api_ms` | 걸린 시간 |
| `num_turns` | 위임이 몇 턴을 돌았나 |
| `usage` · `modelUsage` | 토큰 |

전문은 부록 A 다.

### 3.5 대화 이어가기 — `-p` 에서도 된다

| 잰 것 | 결과 |
|---|---|
| `-p` 에 `--session-id <uuid>` | **동작한다.** 답 문서의 `session_id` 가 정해준 값과 같다 |
| `-p` 에 `--resume <uuid>` | **동작한다.** 앞 회차에 기억시킨 숫자 `4721` 을 답했다 |
| 없는 uuid 로 `--resume` | 즉시 종료 코드 1, stderr `No conversation found with session ID: <uuid>` |
| **이미 쓴 uuid 로 `--session-id` 재사용** | **종료 코드 1, `Error: Session ID <uuid> is already in use.`** |
| 전사 위치 | `~/.claude/projects/<cwd 에서 유도한 이름>/<uuid>.jsonl` |

**마지막에서 둘째 줄이 덤으로 다른 문서의 열린 질문을 닫는다.** app-owned 8절 6번이 *"`--session-id` 를 이미 쓰인 ID 로 두 번 부르면 어떻게 되는지가 확인되지 않았다 — 거절인지, 이어가기인지, 전사가 섞이는지"* 였다. **거절이다.** 그리고 그 대화가 이미 끝난 뒤에도 거절이므로, 동시성 문제가 아니라 **한 번 쓴 ID 는 다시 `--session-id` 로 쓸 수 없다**는 뜻이다.

그래서 이어가기는 반드시 두 걸음으로 갈린다 — **첫 회는 `--session-id`, 이후는 `--resume`.** app-owned 5.5.3 의 흐름이 그대로 위임에도 맞는다.

### 3.6 고정 비용 — 선행 문서 3.1 의 (3) 을 숫자로 바꾼다

위임 한 번의 고정 비용이다. 같은 프롬프트를 3 회씩 돌려 **벽시계에서 `duration_ms`(API 시간) 를 뺐다.**

| 구성 | 고정 비용 (3 회 평균) |
|---|---|
| 사용자 전역 MCP 5 개 + 레포 `.mcp.json` | **3860 ms** |
| 레포 `.mcp.json` 만 (`--strict-mcp-config`) | **2039 ms** |
| MCP 없음 | **1794 ms** |

쪼개면 이렇다.

| 조각 | 크기 |
|---|---|
| 프로세스 기동 | ~1.8 초 |
| 초소형 stdio 서버 하나 | ~0.25 초 |
| 사용자 전역 MCP 집합(원격 3 + 플러그인 2) | ~1.8 초 |

**선행 문서 3.1 의 (3) — *"코드 분석 MCP 는 언어 서버를 띄우므로 부팅이 무겁다. 위임을 잘게 쪼개는 방식과 상성이 나쁘다"* — 은 여전히 맞다.** 다만 무거운 것은 도구가 더하는 층이 아니라 **레포가 이미 갖고 있는 서버**다. 초소형 서버는 0.25 초이고, 언어 서버를 띄우는 MCP 라면 그 자리가 몇 초가 된다. 그리고 그 비용은 사람이 카드를 눌러 세션을 띄울 때도 똑같이 낸다 — **위임이 새로 만드는 비용이 아니라, 위임을 잘게 쪼갤 때만 여러 번 내게 되는 비용이다.** 5.7 이 그것을 다룬다.

### 3.7 동시 위임 — 격리는 성립한다

지문이 서로 다른 가짜 레포 셋을 만들어 동시에 위임했다.

| 잰 것 | 결과 |
|---|---|
| 셋 다 성공했나 | ✅ 전부 종료 코드 0 |
| 각자 자기 레포의 `CLAUDE.md` 지문을 답했나 | ✅ `FP-A-777` · `FP-B-777` · `FP-C-777` |
| 각자의 MCP 프로브가 따로 부팅됐나 | ✅ 레포마다 `tools/call` 1 회 |
| **다른 레포의 지문이 섞였나** | ✅ **아니오** |
| 벽시계 총합 | 28.4 초 (가장 느린 하나의 API 시간이 13.5 초) |

**격리는 성립하고, 완전한 병렬은 아니다.** 셋을 동시에 띄웠는데 총합이 가장 느린 하나보다 길다. API 쪽에서 줄을 서는 것으로 보인다 — 잰 것은 "간섭하지 않는다" 까지이고, "N 배 빨라진다" 는 재지 않았다. **동시 위임은 정확성을 위해 쓰는 것이지 속도를 위해 쓰는 것이 아니다.**

### 3.8 `--mcp-config` 는 관문을 타지 않는다

이 측정은 5.2 의 등록 방식을 정하려고 뒤늦게 더한 것이다.

| 잰 것 | 결과 |
|---|---|
| `.mcp.json` 이 **전혀 없는** 빈 디렉토리에서 `-p --mcp-config <설정>` | 서버가 붙고 도구가 실제로 불린다. `permission_denials` 비어 있음 |
| `--mcp-config` 가 JSON **문자열**도 받나 | ✅ 받는다 (`--mcp-config '{"mcpServers":{}}'` 로 MCP 를 통째로 끈 측정이 3.6 의 셋째 줄이다) |
| 워크디렉토리 `.mcp.json` 을 설정으로 미리 허용할 수 있나 | ❌ **없다** — 아래 |

미리 허용하는 길을 `.claude/settings.local.json` 에 두고 `claude mcp list` 로 쟀다.

| 설정 | 결과 |
|---|---|
| `{"enableAllProjectMcpServers": true}` | 여전히 `⏸ Pending approval` |
| `{"enabledMcpjsonServers": ["kyu-probe"]}` | 여전히 `⏸ Pending approval` |
| `{"disabledMcpjsonServers": ["kyu-probe"]}` | **듣는다** — 목록에서 사라진다 |

**끄는 키는 듣고 켜는 키는 안 듣는다.** 잰 범위를 정확히 적어둔다 — `claude mcp list` 기준이고, **대화형 세션이 그 키를 어떻게 읽는지는 재지 못했다**(대화형 승인을 자동화로 재는 길이 없다). 그러므로 이 설계는 **미리 허용하는 길에 기대지 않는다.** 5.2 가 `--mcp-config` 를 고르는 근거가 이것이다.

---

## 부록 A. 실측 원출력 요약

**환경**: 2026-09-01, `claude` 2.1.252, Linux(WSL2), 모델 `haiku`(권한 매트릭스·시간)·`sonnet`(도구 호출 확정). 부모 세션의 `CLAUDE*` 환경변수를 전부 걷어낸 뒤 실행했다.

### A.1 `claude mcp list` — 승인 없는 새 프로젝트

```
kyu-probe: python3 …/probe_mcp_server.py - ⏸ Pending approval (run `claude` to approve)
```

부팅 로그: 없음. **`-p` 를 한 번 돌린 뒤 다시 불러도 같다.**

### A.2 `-p` 에서 프로브 서버가 부팅된 흔적

```
1788247351.181 boot pid=979914 cwd=…/fake-repo
1788247351.245 recv initialize
1788247351.306 recv notifications/initialized
1788247351.306 recv tools/list
```

`tools/call` 은 없다 — 붙기는 했고 **권한에 막혀 부르지 못했다**(3.3 의 첫 줄).

### A.3 `--dangerously-skip-permissions` 에서 실제로 불린 흔적

```
1788247483.670 boot pid=982002 cwd=…/fake-repo
1788247492.266 recv tools/call
1788247492.266 tools/call repo_fingerprint
```

답: `repo_fingerprint 결과: MCPFP-9182 | target.txt 편집: ✓ "before" → "EDITED" 성공`

### A.4 `--output-format json` 의 최상위 키 (23 개)

```
api_error_status, duration_api_ms, duration_ms, fast_mode_disabled_reason,
fast_mode_state, is_error, modelUsage, num_turns, permission_denials,
queued_turn_count, result, session_id, stop_reason, subagent_stats, subtype,
terminal_reason, time_to_request_ms, total_cost_usd, ttft_ms, ttft_stream_ms,
type, usage, uuid
```

### A.5 권한에 막힌 실행의 답 (기본 모드)

```
exit=0   subtype='success'   is_error=False   num_turns=5
total_cost_usd=0.0417…   session_id='71d7d2aa-…'
permission_denials=[
  {"tool_name":"mcp__kyu-probe__repo_fingerprint","tool_use_id":"toolu_019Yj…","tool_input":{}},
  {"tool_name":"Edit","tool_use_id":"toolu_013xU…",
   "tool_input":{"file_path":"…/target.txt","old_string":"before","new_string":"EDITED"}}]
result='repo_fingerprint 도구와 target.txt 파일 편집에 권한이 필요합니다. 사용자님의 승인을 기다리고 있습니다.'
```

**`target.txt` 는 `before` 그대로였다.**

### A.6 대화 이어가기

```
1 회차  --session-id aa97f289-…   → 돌려준 session_id 가 같음.  result='알겠습니다.'
2 회차  --resume     aa97f289-…   → result='4721'          (1 회차에 기억시킨 숫자)
3 회차  --resume     <없는 uuid>  → exit=1
                                    stderr: No conversation found with session ID: 887b1e33-…
4 회차  --session-id aa97f289-…   → exit=1
                                    stderr: Error: Session ID aa97f289-… is already in use.
전사    ~/.claude/projects/-tmp-…-fake-repo/aa97f289-….jsonl
```

### A.7 고정 비용 (각 3 회, 벽시계 − `duration_ms`)

```
(1) 전역 MCP + 레포 .mcp.json   3828 / 3987 / 3767 ms   평균 3860
(2) 레포 .mcp.json 만            1948 / 2175 / 1996 ms   평균 2039
(3) MCP 없음                     1866 / 1752 / 1765 ms   평균 1794
```

### A.8 동시 위임 셋

```
동시 실행 벽시계 총합 = 28405 ms      (셋 다 exit=0)
repo-a  duration_ms=2275   자기 지문 FP-A-777 ✅  MCP 지문 ✅  다른 레포 지문 섞임 ❌  tools/call=1
repo-b  duration_ms=13481  자기 지문 FP-B-777 ✅  MCP 지문 ✅  다른 레포 지문 섞임 ❌  tools/call=1
repo-c  duration_ms=12258  자기 지문 FP-C-777 ✅  MCP 지문 ✅  다른 레포 지문 섞임 ❌  tools/call=1
```

### A.9 `--agent` 로 가른 에이전트 발견

```
레포 cwd 에서   claude -p --agent probe-agent   → exit=0
레포 밖에서     claude -p --agent probe-agent   → exit=1
   stderr: --agent 'probe-agent' not found. Available agents: claude, coding-agent,
           Explore, general-purpose, Plan, statusline-setup
```

---

## 부록 B. 실측 재현 방법

측정 스크립트는 커밋하지 않는다 — 한 번 답을 얻으면 그 답이 이 문서에 남고, 스크립트는 그 답을 다시 얻는 길일 뿐이다. 다시 재야 할 때 만들 수 있도록 재료만 적어둔다.

**재료 넷을 격리된 스크래치 디렉토리에 만든다.**

| 재료 | 내용 |
|---|---|
| `CLAUDE.md` | 지문 코드 한 줄 (`KYUFP-7431`) 과 "지문을 물으면 그것만 답하라" |
| `.mcp.json` | 아래의 초소형 stdio 서버를 `type: "stdio"` 로 등록. `env` 에 부팅 로그 경로 |
| 초소형 stdio MCP 서버 | `initialize` · `tools/list` · `tools/call` · `ping` 만 답하는 JSON-RPC 루프. **모든 수신을 로그 파일에 적는다** — 이것이 판정 근거다 |
| `.claude/settings.json` · `.claude/agents/probe-agent.md` | `SessionStart` 훅이 로그 파일에 한 줄 적게. 에이전트는 이름만 있으면 된다 |

**판정할 때 지킨 것 셋**

1. **모델의 말을 근거로 쓰지 않는다.** MCP 는 서버가 남긴 로그로, 훅은 훅이 쓴 파일로, 에이전트는 종료 코드로 가른다. 모델은 도구를 안 부르고 답을 지어낼 수 있다 — 실제로 한 번 그랬다(3.1 의 마지막 문단).
2. **부모 세션의 환경을 걷어낸다.** `CLAUDECODE`·`CLAUDE_CODE_*` 를 `env -u` 로 전부 지우고 실행한다.
3. **승인 이력이 없는 새 경로에서 잰다.** `~/.claude.json` 의 `projects` 에 그 경로가 없는 것을 먼저 확인한다. 있으면 3.2 의 측정이 성립하지 않는다.

**시간을 잴 때**: 벽시계에서 답 문서의 `duration_ms` 를 뺀다. 그것이 프로세스 기동 + MCP 부팅이다. API 시간은 회차마다 크게 흔들리지만 그 차이는 안정적이다(A.7).

---

## 부록 C. 확인된 사실

구현 중 다시 조사하지 않도록 모아둔다. 선행 문서들의 부록에 있는 사실은 되풀이하지 않는다.

| 사실 | 근거 |
|---|---|
| 레포 cwd 의 `claude -p` 는 `CLAUDE.md` · `.mcp.json` · `.claude/settings.json`(훅) · `.claude/agents/` 를 **전부** 로드한다 | 2026-09-01 실측 (3.1) |
| `.mcp.json` 승인 관문은 **대화형에만 있다.** `-p` 는 승인 없는 프로젝트의 서버에도 붙는다 | 2026-09-01 실측 (3.2) |
| `-p` 실행은 승인을 **저장하지 않는다** — 뒤에 `claude mcp list` 를 불러도 여전히 `Pending approval` | 2026-09-01 실측 (3.2) |
| `--mcp-config` 로 넘긴 서버는 승인 관문을 타지 않고, **JSON 문자열**도 받는다 | 2026-09-01 실측 (3.8). **대화형에서는 재지 못했다 — 구현 1 단계에서 확인** |
| `enableAllProjectMcpServers` · `enabledMcpjsonServers` 는 `claude mcp list` 기준으로 듣지 않았다. `disabledMcpjsonServers` 는 듣는다 | 2026-09-01 실측 (3.8) |
| `--permission-mode acceptEdits` 는 파일 수정을 허용하지만 **MCP 도구 호출은 막는다** | 2026-09-01 실측 (3.3) |
| `--permission-mode auto` 는 MCP 도구 호출과 파일 수정을 둘 다 허용한다 (사소한 경우에 한해 확인) | 2026-09-01 실측 (3.3) |
| **권한에 막힌 실행도 종료 코드 0 · `subtype: success` 로 끝난다.** `permission_denials` 배열이 유일한 단서다 | 2026-09-01 실측 (3.3) |
| `-p` 에서 `--session-id` 와 `--resume` 이 모두 동작한다 | 2026-09-01 실측 (3.5) |
| **한 번 쓴 대화 ID 는 `--session-id` 로 다시 쓸 수 없다** — `Error: Session ID … is already in use.` 그 대화가 끝난 뒤에도 그렇다 | 2026-09-01 실측 (3.5). **app-owned 8 절 6 번을 닫는다** |
| 위임 한 번의 고정 비용은 MCP 없이 ~1.8 초, 초소형 서버 하나에 ~0.25 초, 사용자 전역 MCP 집합에 ~1.8 초 | 2026-09-01 실측 (3.6) |
| 레포 셋에 동시 위임해도 설정이 섞이지 않는다. 다만 벽시계가 가장 느린 하나보다 길다 — 완전한 병렬은 아니다 | 2026-09-01 실측 (3.7) |
| `claude --help`: `-p` 는 workspace trust 대화를 건너뛰고, **검증에 실패한 설정 파일을 조용히 무시한다** | `claude --help` (2.1.252). 위임이 레포의 깨진 `settings.json` 을 만나도 알려주지 않는다는 뜻이다 |
