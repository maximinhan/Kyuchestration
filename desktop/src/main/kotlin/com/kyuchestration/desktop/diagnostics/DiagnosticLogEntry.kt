package com.kyuchestration.desktop.diagnostics

import java.io.PrintWriter
import java.io.StringWriter

/**
 * 기록에 남길 수 있는 사건 전부.
 *
 * **갈래를 닫아 두는 것이 이 타입의 요점이다.** 자유로운 문자열 한 줄을 받는 `log(String)` 이
 * 있었다면 이 앱의 어느 자리에서든 무엇이든 적을 수 있고, 그러면 "기록에 비밀이 들어가지 않는가"
 * 는 그 부르는 자리를 전부 읽어야만 답할 수 있는 물음이 된다. 남길 수 있는 것을 여기 열 가지로
 * 못 하나씩 정해 두면, 그 물음의 답은 이 파일 하나를 읽으면 끝난다.
 *
 * 그래서 이 타입들 어디에도 비밀이 실릴 자리가 없다. 비밀이 앱을 지나는 통로는 셋이다 — 엔진에
 * 건네는 stdin(KyuCommandRunner.standardInput), 엔진이 돌려주는 claude 토큰, 사용자가 붙여넣는
 * 로그인 코드. 셋 중 어느 것을 받는 필드도 여기 없다. 엔진 호출을 기록하는 [EngineCallFailed] 는
 * 인자와 stderr 만 받고, 브라우저 로그인 실패를 적는 [ClaudeBrowserLoginFailed] 는 종료 코드만
 * 받는다 — claude 의 stderr 는 화면까지만 가고 이 파일을 지나지 않는다
 * (ClaudeAuthenticationFailure 의 머리말이 그 근거다).
 *
 * 뒷날 누가 실패를 더 자세히 남기려고 그 스트림들을 얹으려 하면, 그 결정은 이 파일을 고치는
 * 일이 되어 눈에 띈다.
 */
sealed interface DiagnosticLogEntry {

    /** 시각 뒤에 붙는 한 줄. 파일을 훑는 사람이 이 줄만 읽고도 무슨 일이었는지 알아야 한다. */
    val summary: String

    /** 한 줄에 담기지 않는 것 — kyu 의 stderr, 예외의 스택. 없으면 null. */
    val detail: String?
        get() = null

    /**
     * 앱이 떴다.
     *
     * 기록의 첫 줄이 이것인 이유는 판 맞춤이다. 받은 기록을 읽을 때 가장 먼저 묻게 되는 것이
     * "어느 판에서 난 일인가" 인데, 그것이 파일 안에 없으면 되물어야 한다.
     */
    data class ApplicationStarted(
        val versionLabel: String,
        val operatingSystemName: String,
        val javaRuntimeVersion: String,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = "앱 시작 — $versionLabel · $operatingSystemName · JVM $javaRuntimeVersion"
    }

    /**
     * 엔진을 불렀는데 0 이 아닌 코드로 끝났다.
     *
     * @param arguments kyu 뒤에 붙인 인자 전부. 사람이 같은 것을 터미널에서 그대로 다시 부를 수
     *   있어야 기록이 재현으로 이어진다. 토큰은 여기 오지 않는다 — 인자는 같은 머신의 다른
     *   사용자가 ps 로 읽으므로, 애초에 토큰을 인자로 넘기지 않는 것이 엔진과의 계약이다.
     */
    data class EngineCallFailed(
        val arguments: List<String>,
        val exitCode: Int,
        val standardError: String,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = "엔진 호출 실패 — kyu ${arguments.joinToString(" ")} (종료 코드 $exitCode)"

        override val detail: String?
            get() = standardError.trim().ifBlank { null }
    }

    /**
     * 엔진을 부르지도 못했다 — 실행 파일이 없거나, 찾은 뒤 지워졌거나, 실행 권한이 없거나.
     *
     * 위의 것과 갈라 둔다. 종료 코드가 없다는 것이 사실의 차이이고, 그것을 억지로 하나로 합치면
     * 기록을 읽는 쪽이 "종료 코드 -1" 같은 지어낸 값을 만나게 된다.
     */
    data class EngineCallCouldNotStart(
        val arguments: List<String>,
        val reason: String,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = "엔진을 부르지 못함 — kyu ${arguments.joinToString(" ")}: $reason"
    }

    /** 앱이 세션 하나를 보유하기 시작했다. */
    data class SessionStarted(
        val targetLabel: String,
        val resumingConversation: Boolean,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = "세션 시작 — $targetLabel (${if (resumingConversation) "대화 이어가기" else "새 대화"})"
    }

    /**
     * 앱이 끝내지 않았는데 세션이 0 이 아닌 코드로 끝났다.
     *
     * 잘 끝난 세션은 여기 오지 않는다. 사용자가 세션 끝내기를 누른 자리도, 세션 안에서 `/exit`
     * 해서 0 으로 끝난 자리도 사용자가 시킨 일이라 진단할 것이 없다 — 그것까지 남기면 기록이
     * 정상적인 사용으로 차서 정작 봐야 할 줄이 그 아래 묻힌다.
     *
     * @param exitCode 세션이 남긴 종료 코드. 예외로 끝나 알 수 없으면 null.
     * @param resumeFailureSuspected 이어가려던 대화를 열지 못한 것으로 보이는가
     *   (화면이 쓰는 것과 같은 판단이다 — terminal/ResumeFailureSuspicion.kt).
     */
    data class SessionEndedUnexpectedly(
        val targetLabel: String,
        val exitCode: Int?,
        val resumeFailureSuspected: Boolean,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = buildString {
                append("세션이 스스로 끝남 — $targetLabel (종료 코드 ${exitCode ?: "알 수 없음"})")
                if (resumeFailureSuspected) {
                    append(" · 이어가던 대화를 열지 못한 것으로 보임")
                }
            }
    }

    /** 세션에 들어가지 못했다. */
    data class SessionEntryFailed(
        val targetLabel: String,
        val failureMessage: String,
        val guidance: String,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = "세션 진입 실패 — $targetLabel: $failureMessage"

        override val detail: String?
            get() = guidance.trim().ifBlank { null }
    }

    /**
     * 앱이 claude 에게 자격 증명이 있는지 물었다.
     *
     * 성공도 남긴다. 이 앱의 다른 기록은 실패만 남기지만, 여기는 "세션이 왜 안 열리는가" 를
     * 되짚는 첫 자리다 — 로그인됐다고 판정한 실행에서 세션이 401 로 끝났다면, 그 두 줄이
     * 나란히 있어야 어긋난 지점이 보인다.
     *
     * @param storedTokenUsed 앱이 맡아 둔 토큰을 실어서 물었는가. 토큰 값은 싣지 않는다.
     */
    data class ClaudeCredentialsChecked(
        val credentialsPresent: Boolean,
        val storedTokenUsed: Boolean,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = "claude 자격 증명 확인 — ${if (credentialsPresent) "있음" else "없음"}" +
                " (맡아 둔 토큰 ${if (storedTokenUsed) "실어서" else "없이"} 물음)"
    }

    /**
     * claude 를 띄우지 못했다 — 이 머신에 없거나 실행 권한이 없다.
     *
     * @param reason 예외가 적은 것. claude 를 **띄우기 전에** 난 실패라 사용자가 친 글자도
     *   맡아 둔 토큰도 이 문자열에 닿지 않는다.
     */
    data class ClaudeCliCouldNotRun(val reason: String) : DiagnosticLogEntry {
        override val summary: String
            get() = "claude 를 부르지 못함 — $reason"
    }

    /**
     * 브라우저 로그인이 0 이 아닌 코드로 끝났다.
     *
     * **종료 코드만 받는다.** claude 가 stderr 에 적은 것을 여기 실으면, 사용자가 방금 붙여넣은
     * 코드가 그 문자열에 섞여 있을 가능성을 이 파일이 떠안게 된다 — 실패 갈래를 하나만 재고서
     * 섞이지 않는다고 단정할 수 없다. 그 문구는 화면까지만 간다.
     */
    data class ClaudeBrowserLoginFailed(val exitCode: Int) : DiagnosticLogEntry {
        override val summary: String
            get() = "claude 브라우저 로그인 실패 — 종료 코드 $exitCode"
    }

    /**
     * 붙여넣은 토큰을 앤트로픽이 거절했다.
     *
     * @param claudeMessage claude 가 `result` 에 적은 것. 앤트로픽이 그 토큰에 대해 한 말이라
     *   사용자가 친 글자가 섞이지 않는다 — 그래서 이 갈래는 이유를 그대로 남긴다.
     *   401 과 다른 실패(망·한도)를 되짚어 가르는 유일한 근거다.
     */
    data class ClaudePastedTokenRefused(val claudeMessage: String) : DiagnosticLogEntry {
        override val summary: String
            get() = "claude 토큰 거절 — $claudeMessage"
    }

    /**
     * 맡겨 둔 claude 토큰을 엔진에게 묻지 못했다.
     *
     * 화면에는 뜨지 않는 실패다. 저장소가 답하지 않아도 로그인 자체는 할 수 있어서 앱은 그대로
     * 나아가는데, 그러면 "왜 매번 다시 묻는가" 의 이유가 어디에도 남지 않는다.
     *
     * @param reason kyu 가 stderr 에 적은 것. 그 스트림에 토큰이 실리지 않는 것은 엔진 쪽
     *   시험이 고정한다(claude_auth_test.go).
     */
    data class ClaudeTokenStoreUnavailable(val reason: String) : DiagnosticLogEntry {
        override val summary: String
            get() = "claude 토큰 저장소가 답하지 않음 — $reason"
    }

    /**
     * 아무도 받지 않은 예외가 스레드를 끝냈다.
     *
     * 이 갈래가 있어야 기록이 "앱이 예상한 실패" 를 넘어선다. 나머지 아홉은 이 앱이 이미 알고
     * 있는 실패이고, 진단이 정말 필요한 것은 알지 못했던 쪽이다.
     */
    data class UnhandledFailure(
        val threadName: String,
        val failure: Throwable,
    ) : DiagnosticLogEntry {
        override val summary: String
            get() = "미처리 예외 — $threadName 스레드: ${failure::class.simpleName}: ${failure.message}"

        override val detail: String
            get() = StringWriter().also { PrintWriter(it).use(failure::printStackTrace) }.toString()
    }
}
