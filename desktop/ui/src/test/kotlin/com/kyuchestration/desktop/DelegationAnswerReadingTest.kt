package com.kyuchestration.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 위임 도구가 답한 문서를 카드가 읽는다(설계 5.7 · orchestration 5.4.2).
 *
 * **넣는 글자가 진짜다.** 아래 셋은 이 브랜치에서 빌드한 `kyu mcp serve` 에게 `run_in_repo` 를
 * 실제로 불러 받은 답이다(2026-09-09, 격리된 스크래치의 가짜 워크디렉토리). 손으로 지어낸
 * 문서로 시험하면 확인되는 것은 우리가 상상한 엔진이고, 진짜와 어긋나는 자리는 앱을 띄운
 * 사람이 처음 발견한다.
 *
 * 손댄 것은 경로뿐이다 — 스크래치의 긴 절대경로를 `/tmp/workdir` 로 줄였다.
 */
class DelegationAnswerReadingTest {

    @Test
    fun `끝난 위임은 답과 숫자를 그대로 들고 온다`() {
        val delegation = assertNotNull(delegationAnswerOrNull(COMPLETED_DELEGATION_ANSWER))

        assertEquals("proj-a", delegation.repo)
        assertTrue(delegation.result.startsWith("DELEGATE-2026"))
        assertNull(delegation.incomplete, "끝낸 위임에는 이유가 없다")
        assertEquals(emptyList(), delegation.permissionDeniedTools)
        assertEquals(9050, delegation.durationMillis)
        assertEquals(2, delegation.turnCount)
        assertEquals(0.31796074999999996, delegation.costUsd)
        assertEquals(false, delegation.resumedConversation)
        assertTrue(delegation.logPath.endsWith(".json"), "원출력 자리를 카드가 그대로 보인다")
    }

    @Test
    fun `권한에 막힌 위임은 완결되지 않았다는 것을 문장으로 들고 온다`() {
        // **이 갈래가 이 파일의 요점이다.** 막힌 위임도 종료 코드 0 으로 끝나므로(orchestration 3.3),
        // 카드가 "완료" 라고 말하지 않게 막는 근거는 이 문장 하나뿐이다.
        val delegation = assertNotNull(delegationAnswerOrNull(PERMISSION_BLOCKED_DELEGATION_ANSWER))

        assertEquals(0, delegation.exitCode, "막힌 실행도 0 으로 끝나는 것이 이 갈래의 전제다")
        assertNotNull(delegation.incomplete)
        assertTrue("완결되지 않았습니다" in delegation.incomplete)
        assertEquals(listOf("Edit", "mcp__proj__deploy"), delegation.permissionDeniedTools)
        assertEquals(true, delegation.resumedConversation)
    }

    @Test
    fun `위임이 걸리기도 전에 끝난 물음은 문서가 아니다`() {
        // 없는 레포 이름은 사람이 읽을 문장 하나로 온다(refuseDelegation). 카드는 그 문장을
        // 그대로 보인다 — 지어낸 필드로 채운 문서보다 낫다.
        assertNull(delegationAnswerOrNull(REFUSED_DELEGATION_ANSWER))
    }

    @Test
    fun `우리 문서가 아닌 JSON 도 문서가 아니다`() {
        // 다른 MCP 도구의 답이 JSON 이라는 이유로 위임 카드에 실리면, 그 카드는 없는 레포와
        // 없는 비용을 말한다.
        assertNull(delegationAnswerOrNull("""{"repo":"proj-a"}"""), "우리 문서에는 permissionDenials 가 늘 있다")
        assertNull(delegationAnswerOrNull("이건 JSON 도 아니다"))
    }

    private companion object {

        /** 진짜 위임 하나가 끝난 답. */
        const val COMPLETED_DELEGATION_ANSWER: String = """{
  "repo": "proj-a",
  "result": "DELEGATE-2026\n\n`NOTE.md:1` 의 지문 문자열입니다. 파일은 수정하지 않았습니다.",
  "incomplete": null,
  "permissionDenials": [],
  "conversationId": "6dc527ca-5bcf-46f7-b33b-572d0c92e3b1",
  "resumed": false,
  "costUsd": 0.31796074999999996,
  "durationMs": 9050,
  "numTurns": 2,
  "exitCode": 0,
  "logPath": "/tmp/workdir/.coord/runs/proj-a-20260909-032050.358364-6dc527ca.json"
}
"""

        /**
         * 권한에 막힌 위임의 답.
         *
         * 이 답도 엔진이 실제로 낸 것이다. 막힌 `claude` 만 흉내 냈다 — 권한 관문이 실제로 닫히는
         * 상황을 만드는 것보다, 그 판정이 어떤 줄로 오는지(`permission_denials`)를 이미 잰
         * 다음이라 그 줄을 내는 프로그램을 PATH 에 두는 편이 정확하다. 엔진의 Go 시험이 같은
         * 방법을 쓴다(mcp_run_in_repo_test.go).
         */
        const val PERMISSION_BLOCKED_DELEGATION_ANSWER: String = """{
  "repo": "proj-a",
  "result": "권한이 필요합니다 — 아무것도 고치지 못했습니다.",
  "incomplete": "이 위임은 완결되지 않았습니다 — 권한에 막혀 부르지 못한 도구가 있습니다: Edit, mcp__proj__deploy. 무엇을 허용할지 사용자에게 물어보세요 (원출력: /tmp/workdir/.coord/runs/proj-a-20260909-032122.476620-6dc527ca.json)",
  "permissionDenials": [
    "Edit",
    "mcp__proj__deploy"
  ],
  "conversationId": "11111111-2222-4333-8444-555555555555",
  "resumed": true,
  "costUsd": 0.0123,
  "durationMs": 4321,
  "numTurns": 2,
  "exitCode": 0,
  "logPath": "/tmp/workdir/.coord/runs/proj-a-20260909-032122.476620-6dc527ca.json"
}
"""

        /** 없는 레포 이름으로 부른 위임의 답. */
        const val REFUSED_DELEGATION_ANSWER: String =
            "없는 레포입니다: proj-없는것\n이 워크디렉토리의 레포: proj-a"
    }
}
