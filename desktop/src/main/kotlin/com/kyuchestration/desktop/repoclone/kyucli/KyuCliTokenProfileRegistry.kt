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

    /**
     * `kyu auth remove <이름>` — 이 흐름에서 유일하게 기계용 문서를 읽지 않는 걸음이다.
     *
     * **--json 을 붙이지 않는다.** 엔진이 그 옵션을 받지 않아서다: `auth remove` 는 인자를 정확히
     * 하나만 받고, 하나 더 붙이면 "지울 프로필 이름 하나가 필요합니다" 로 거절한다(auth.go).
     * 붙였다면 모든 제거가 실패하는데, 그 실패의 종료 코드가 진짜 거절과 같아 화면에서 가려지지
     * 않는다 — 사용자는 "지워지지 않는다" 만 보게 된다.
     *
     * 문서가 없어도 충분하다. 이 걸음이 앱에게 돌려줄 것이 없기 때문이다 — 지워졌는지만 알면
     * 되고 그 답은 종료 코드가 말한다. 지운 뒤의 목록은 어차피 엔진에게 다시 묻는다.
     * (문서가 필요해지는 날은 제거가 무언가를 답해야 할 때인데, 그런 날이 오면 엔진 쪽에
     * --json 을 더하는 것이 맞는 자리다 — 앱이 사람용 문장을 파싱하기 시작하면 안 된다.)
     */
    override fun removeProfile(profileName: String) {
        kyuCommandRunner.runCloneStep(listOf("auth", "remove", profileName)).failIfKyuRefused()
    }
}
