package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationStateHolder
import com.kyuchestration.desktop.claudeauth.kyucli.KyuCliClaudeTokenStore
import com.kyuchestration.desktop.claudeauth.process.ProcessClaudeCliAuthentication
import com.kyuchestration.desktop.dashboard.WorkDirDashboardStateHolder
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.engine.EngineInstallationStateHolder
import com.kyuchestration.desktop.engine.bundled.placeBundledEngineWhereItCanRun
import com.kyuchestration.desktop.engine.githubrelease.GitHubReleaseEngineInstaller
import com.kyuchestration.desktop.initialization.WorkDirInitializationStateHolder
import com.kyuchestration.desktop.kyu.ProcessKyuCommandRunner
import com.kyuchestration.desktop.kyu.findKyuExecutable
import com.kyuchestration.desktop.kyu.managedEngineDirectory
import com.kyuchestration.desktop.kyu.planFileExistsIn
import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.repoclone.RepositoryCloneStateHolder
import com.kyuchestration.desktop.repoclone.kyucli.KyuCliGitHubRepositoryCatalog
import com.kyuchestration.desktop.repoclone.kyucli.KyuCliTokenProfileRegistry
import com.kyuchestration.desktop.repoclone.kyucli.KyuCliWorkDirRepositoryCloner
import com.kyuchestration.desktop.terminal.chat.ChatSessionStateHolder
import com.kyuchestration.desktop.terminal.chat.ProcessChatSessionOpener
import com.kyuchestration.desktop.terminal.kyucli.KyuCliChildSessionCommandSource
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirInitializer
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirObserver
import kotlinx.coroutines.CoroutineScope

/**
 * 코어의 조립이 일어나는 유일한 자리.
 *
 * 상태 홀더는 자기 포트만 안다 — 목록은 관찰 포트를, 여는 걸음은 초기화 포트를, 챗은 진입 포트를. "그 포트 뒤에 kyu
 * 프로세스가 있다" 는 사실을 아는 파일이 여기 하나뿐이라, 엔진을 바꿔 끼울 때 고칠 곳도 여기 하나다.
 *
 * 돌려주는 것은 [ScreenFacingCore] 뿐이다. 홀더를 돌려주지 않는 것이 이 함수의 경계다 — 이것을 받는 것은 화면이 아니라 화면에
 * 틀을 잇는 자리(serveScreen)이고, 화면은 그 틀 위의 거울로 코어를 본다(core-ui-split-design.md 6.2 · 6.5).
 *
 * @param coroutineScope 코어의 단일 스레드 스코프(startCore). 홀더들이 이 스레드를 함께 쓴다.
 */
fun assembleInProcessScreenFacingCore(coroutineScope: CoroutineScope, diagnosticLog: DiagnosticLog): ScreenFacingCore {
    // 관찰과 초기화가 같은 실행기를 함께 쓴다. 실행기는 부를 때마다 kyu 를 새로 찾는 무상태라 둘로 나눌 이유가 없다.
    val kyuCommandRunner = ProcessKyuCommandRunner(diagnosticLog = diagnosticLog)

    // 앱의 첫 갈림길. 다른 홀더보다 먼저 세우는 것이 뜻이다 — 엔진이 없으면 나머지 화면은 뜨지 않는다. 세우는 것 자체가
    // 걸음이기도 하다: 어디에도 엔진이 없으면 이 홀더가 그 자리에서 곧바로 받기 시작한다.
    val engineInstallation = EngineInstallationStateHolder(
        // 이 두 자리도 기록을 받는다. 여기서 걸린 실행은 설치 화면에 멈춰 서고 그 뒤로 엔진을 부르는 일이 하나도 없어서,
        // 넘기지 않으면 그 실행의 기록이 "앱이 시작했다" 한 줄로 끝난다 — 맥이 격리해 둔 바이너리처럼 원격으로 봐야 하는
        // 실패가 그렇게 끝난다.
        engineInstaller = GitHubReleaseEngineInstaller(diagnosticLog = diagnosticLog),
        findEngineExecutable = ::findKyuExecutable,
        placeBundledEngine = { placeBundledEngineWhereItCanRun(diagnosticLog = diagnosticLog) },
        coroutineScope = coroutineScope,
    )
    // 엔진 다음 갈림길. 세션을 여는 걸음이 전부 claude 를 띄우는 일이라, 로그인되지 않은 채로는 워크디렉토리를 열어 봐야
    // 첫 대화에서 막힌다(chat-ui-design.md 7.2).
    val claudeAuthentication = ClaudeAuthenticationStateHolder(
        // claude 에게 직접 묻는다. 엔진에는 claude 에게 물을 클라이언트가 없고, 이것은 세션이 무엇을 띄울지의 물음이 아니라
        // 앱이 자기 온보딩을 위해 하는 물음이다.
        claudeCliAuthentication = ProcessClaudeCliAuthentication(),
        // 맡아 두는 일만 엔진에게 시킨다. 이 도구의 비밀이 사는 자리가 거기 하나다.
        claudeTokenStore = KyuCliClaudeTokenStore(kyuCommandRunner),
        coroutineScope = coroutineScope,
        diagnosticLog = diagnosticLog,
    )
    return InProcessScreenFacingCore(
        engineInstallation = engineInstallation,
        claudeAuthentication = claudeAuthentication,
        workDirDashboard = WorkDirDashboardStateHolder(
            workDirObserver = KyuCliWorkDirObserver(kyuCommandRunner),
            planFileExists = ::planFileExistsIn,
            coroutineScope = coroutineScope,
        ),
        workDirInitialization = WorkDirInitializationStateHolder(
            workDirInitializer = KyuCliWorkDirInitializer(kyuCommandRunner),
            planFileExists = ::planFileExistsIn,
            coroutineScope = coroutineScope,
        ),
        repositoryClone = RepositoryCloneStateHolder(
            tokenProfileRegistry = KyuCliTokenProfileRegistry(kyuCommandRunner),
            gitHubRepositoryCatalog = KyuCliGitHubRepositoryCatalog(kyuCommandRunner),
            workDirRepositoryCloner = KyuCliWorkDirRepositoryCloner(kyuCommandRunner),
            coroutineScope = coroutineScope,
        ),
        chatSessions = ChatSessionStateHolder(
            // 앱이 스스로 조립하지 않고 엔진에게 묻는다(설계 원칙 11).
            sessionCommandSource = KyuCliSessionCommandSource(kyuCommandRunner),
            chatSessionOpener = ProcessChatSessionOpener(),
            // 하위 세션도 엔진이 조립한다(원칙 11) — 앱이 넘기는 것은 상위가 고른 것과 누가 살아 있는가뿐이다.
            childSessionCommandSource = KyuCliChildSessionCommandSource(kyuCommandRunner),
            // 세션을 띄우는 순간의 값을 묻는다. 조립 시점에는 아직 인증 화면을 지나기 전이라 값이 없고, 지나고 나면 이
            // 자리가 그것을 답한다.
            claudeAuthToken = { claudeAuthentication.tokenForSessions.value },
            coroutineScope = coroutineScope,
            diagnosticLog = diagnosticLog,
        ),
        // 받아 둘 자리는 앱이 도는 동안 바뀌지 않는다. 그릴 때마다 홈 디렉토리와 os.name 을 다시 읽을 이유가 없다.
        engineDirectoryLabel = managedEngineDirectory().toString(),
        coroutineScope = coroutineScope,
    )
}
