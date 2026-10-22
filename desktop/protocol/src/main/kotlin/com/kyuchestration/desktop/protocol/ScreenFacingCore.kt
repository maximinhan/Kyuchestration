package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import kotlinx.coroutines.flow.StateFlow

/**
 * 화면이 코어에 닿는 유일한 자리(core-ui-split-design.md 6.2).
 *
 * 화면은 상태 홀더를 모른다. 여기 있는 상태를 읽고 여기 있는 명령을 부를 뿐이다. 이 타입이 있는 이유는 지금 그 방향을
 * 지키는 것이다 — 화면 패키지가 홀더 · 프로세스를 쥔 타입 · kyu 어댑터에 닿으면 ScreenBoundaryArchitectureTest 가 실패하고,
 * 화면이 코어에 닿을 수 있는 길로 남는 것은 이 타입 하나다. 홀더 여섯 사이의 조립 결정(엔진 → 인증, 초기화 → 대시보드,
 * 워크디렉토리를 닫는 네 걸음)도 화면이 아니라 이 뒤에 있다. 그래서 두 가지를 지킨다.
 *
 * - **건너는 것은 값뿐이다**(원칙 22). 프로세스 핸들 · 람다 · 객체 정체성을 내놓지도 받지도 않는다. 보유 세션은
 *   대상만, 관문 물음은 코어가 지은 id 로 가리킨다.
 * - **명령은 대상을 싣는다.** 화면이 무엇을 보는지 코어는 모른다(6.8 (가)). 열린 워크디렉토리처럼 코어가 이미 아는
 *   것은 싣지 않는다 — 두 자리가 같은 사실을 들면 어긋날 수 있다.
 *
 * 명령은 결과를 돌려주지 않는다. 결과는 언제나 상태의 변화로 온다(6.4 의 `reply` 판단) — 결과가 오는 길이 둘이면
 * 화면이 둘을 맞춰야 한다.
 *
 * 구현은 둘이다. 코어 모듈의 InProcessScreenFacingCore 는 홀더를 직접 부르고, 화면 모듈의 거울(CoreMirror)은 틀 줄을 건너
 * 그것을 비춘다(6.5). 화면은 거울만 본다. 그래서 이 타입은 두 모듈이 함께 보는 protocol 모듈에 있다 — 상태를 StateFlow 로
 * 내놓으므로 이 모듈이 코루틴을 의존하는 이유이기도 하다.
 */
interface ScreenFacingCore {

    val engineInstallationState: StateFlow<EngineInstallationState>

    val claudeAuthenticationState: StateFlow<ClaudeAuthenticationState>

    val workDirDashboardState: StateFlow<WorkDirDashboardState>

    val workDirInitializationState: StateFlow<WorkDirInitializationState>

    /** 세션마다의 대화. 화면이 자기가 보는 세션의 것을 꺼내 그린다(6.8 (가)). */
    val chatConversations: StateFlow<Map<SessionTarget, ChatConversation>>

    /** 사람이 띄우라고 한 세션 중 아직 보유하지 못한 것 — 띄우는 중이거나 띄우지 못했다. */
    val chatSessionEntries: StateFlow<Map<PersonOpenedSessionTarget, SessionEntryState>>

    /** 코어가 보유 중인 세션의 대상. 세션 자체(프로세스 · 소켓)는 코어 밖으로 나오지 않는다. */
    val heldChatSessionTargets: StateFlow<Set<SessionTarget>>

    val sessionTree: StateFlow<SessionTree>

    val childSessionGateQuestions: StateFlow<List<ChildSessionGateQuestion>>

    val repositoryCloneState: StateFlow<RepositoryCloneState>

    /** 엔진을 받아 두는 자리. 엔진은 코어 옆에 놓이므로 코어 OS 의 사실이다(6.2 (다)). */
    val engineDirectoryLabel: String

    fun installEngine()

    fun lookForEngineAgain()

    fun checkClaudeCredentials()

    fun startClaudeBrowserLogin()

    fun submitClaudeBrowserLoginCode(code: String)

    fun cancelClaudeBrowserLogin()

    /** 토큰을 싣는다 — 비밀이 경계를 건너는 두 명령 중 하나다(6.11). */
    fun useClaudeToken(token: String)

    /**
     * 화면의 파일 선택기가 고른 자리를 워크디렉토리로 연다. 초기화가 필요하면 그 걸음도 코어가 잇는다.
     *
     * @param hostPath 화면 OS 의 경로 문자열. 화면 경로가 경계에 오르는 자리는 여기 하나이고, 푸는 것은 코어다(설계 6.9).
     */
    fun openWorkDir(hostPath: String)

    fun initializeOpenedWorkDir()

    fun refreshWorkDir()

    /** 열린 워크디렉토리를 닫는다 — 그 안의 세션을 끝내고, 그 워크디렉토리에 걸린 걸음을 함께 놓는다. */
    fun closeWorkDir()

    fun enterSession(target: PersonOpenedSessionTarget, conversationChoice: SessionConversationChoice)

    /** 띄우지 못한 진입의 실패를 닫는다. 화면이 그 세션을 떠날 때 부른다 — 사람이 어디를 보는지는 화면만 안다. */
    fun dismissSessionEntryFailure(target: PersonOpenedSessionTarget)

    fun sendUserMessage(target: SessionTarget, text: String)

    fun interruptTurn(target: SessionTarget)

    fun answerPermission(target: SessionTarget, toolUseId: String, choice: PermissionCardChoice)

    fun endSession(target: SessionTarget)

    fun answerGate(gateQuestionId: String, approved: Boolean)

    /** 인자가 없다 — 받을 자리(열린 워크디렉토리)도, 이미 받은 레포도 코어가 방금 관찰한 목록이 안다. */
    fun openRepositoryClone()

    fun closeRepositoryClone()

    fun chooseTokenProfile(profileName: String)

    fun startRemovingTokenProfile(profileName: String)

    fun stopRemovingTokenProfile()

    fun removeChosenTokenProfile()

    fun startRegisteringTokenProfile()

    fun stopRegisteringTokenProfile()

    /** 토큰을 싣는다 — 비밀이 경계를 건너는 두 명령 중 하나다(6.11). */
    fun registerTokenProfile(profileName: String, token: String)

    fun continueWithRegisteredTokenProfile()

    fun chooseRepositoryOwner(ownerLogin: String)

    fun changeRepositorySearchText(searchText: String)

    fun toggleRepositorySelection(repositoryFullName: String)

    fun cloneSelectedRepositories()

    fun retryFailedCloneStep()

    /**
     * 보유한 세션을 전부 끝내고 프로세스가 실제로 끝날 때까지 기다린다 — 창을 닫는 자리다.
     *
     * 화면이 보는 구현(CoreMirror)은 이것을 명령으로 보내지 않는다 — 코어로 가는 줄을 닫고, 코어가 그 끝을 보고 모든 세션을
     * 끝낸 뒤 자기 줄을 닫을 때까지 기다린다(serveScreen). 코어가 갈라진 뒤(7 절 2 단계)에는 그 줄이 stdin 이 되어, 화면이
     * 크래시해도 코어는 같은 끝을 본다(6.13).
     */
    suspend fun endAllSessions()
}
