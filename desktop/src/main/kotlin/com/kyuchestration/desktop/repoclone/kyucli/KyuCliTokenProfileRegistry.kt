package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandRunner
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

    override fun listProfiles(): List<TokenProfile> {
        val result = kyuCommandRunner.runCloneStep(listOf("auth", "list", "--json"))
        result.failIfKyuRefused()
        return readCloneStepDocument(result.standardOutput, ::parseKyuAuthListOutput)
    }

    override fun registerProfile(profileName: String, token: String): RegisteredTokenProfile {
        val result = kyuCommandRunner.runCloneStep(
            arguments = listOf("auth", "add", profileName, "--json"),
            // 토큰은 인자가 아니라 stdin 으로 간다. 인자는 같은 머신의 다른 사용자가 ps 로 읽는다.
            standardInput = token,
        )
        // 거절당한 토큰은 저장되지 않는다(auth_add.go). kyu 는 그 사실까지 stderr 에 적어 두므로
        // 그 말을 그대로 올린다 — 화면이 "다시 붙여넣으세요" 로 되돌릴 근거가 그것이다.
        result.failIfKyuRefused()
        return readCloneStepDocument(result.standardOutput, ::parseKyuAuthAddOutput)
    }
}
