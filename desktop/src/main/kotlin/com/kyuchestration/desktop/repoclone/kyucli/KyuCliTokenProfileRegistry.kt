package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import com.kyuchestration.desktop.repoclone.RegisteredTokenProfile
import com.kyuchestration.desktop.repoclone.TokenProfile
import com.kyuchestration.desktop.repoclone.TokenProfileRegistry

/**
 * kyu CLI 를 엔진으로 불러 토큰 프로필을 보고 등록한다.
 *
 * 앱은 토큰을 저장하지도 읽지도 않는다. 키체인·secret-service·평문 파일을 고르는 규칙은 엔진의
 * secretstore 안에 있고, 그것을 코틀린으로 한 번 더 옮기면 앱으로 등록한 토큰과 터미널에서
 * 등록한 토큰이 다른 자리에 놓이는 날이 온다.
 */
class KyuCliTokenProfileRegistry(private val kyuCommandRunner: KyuCommandRunner) : TokenProfileRegistry {

    override fun listProfiles(): List<TokenProfile> =
        parseDocument(runKyu(listOf("auth", "list", "--json")), ::parseKyuAuthListOutput)

    override fun registerProfile(profileName: String, token: String): RegisteredTokenProfile =
        parseDocument(
            // 토큰은 인자가 아니라 stdin 으로 간다. 인자는 같은 머신의 다른 사용자가 ps 로 읽는다.
            runKyu(listOf("auth", "add", profileName, "--json"), token),
            ::parseKyuAuthAddOutput,
        )

    private fun runKyu(arguments: List<String>, token: String? = null): KyuCommandResult {
        val result = try {
            kyuCommandRunner.run(arguments, standardInput = token)
        } catch (failure: KyuCommandFailure) {
            // 실행기는 "부르지 못했다" 는 사실만 던진다. 레포를 받으려던 사람에게 할 말로 옮기는
            // 것이 이 어댑터의 몫이다.
            throw when (failure) {
                is KyuCommandFailure.ExecutableNotFound -> CloneStepFailure.KyuExecutableNotFound()
                is KyuCommandFailure.FailedToStart -> CloneStepFailure.KyuFailedToStart(failure.processStartFailure)
            }
        }

        if (result.exitCode != 0) {
            // kyu 는 거절 이유를 stderr 에 사람 말로 적는다(거절당한 토큰이면 저장하지 않았다는
            // 사실까지). 그것을 그대로 올리는 편이 이쪽에서 다시 지어낸 문구보다 정확하다.
            throw CloneStepFailure.KyuExitedWithFailure(
                exitCode = result.exitCode,
                standardError = result.standardError.trim(),
            )
        }
        return result
    }

    private fun <T> parseDocument(result: KyuCommandResult, parse: (String) -> T): T = try {
        parse(result.standardOutput)
    } catch (failure: KyuDocumentFailure) {
        throw when (failure) {
            is KyuDocumentFailure.UnsupportedSchemaVersion -> CloneStepFailure.UnsupportedSchemaVersion(
                actualSchemaVersion = failure.actualSchemaVersion,
                supportedSchemaVersion = failure.supportedSchemaVersion,
            )

            is KyuDocumentFailure.Unreadable -> CloneStepFailure.UnreadableKyuOutput(failure.parseFailure)
        }
    }
}
