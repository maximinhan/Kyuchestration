package com.kyuchestration.desktop.claudeauth.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * `claude auth login` 이 찍는 것에서 주소를 뽑는 규칙.
 *
 * 자료는 실측 원문이다(chat-ui-design.md 7.3 가). 주소가 OSC 8 하이퍼링크로 감싸여 **두 번**
 * 실려 오는 것이 이 파싱의 전부이고, 그것을 모르고 이어 읽으면 열리지 않는 주소를 얻는다.
 *
 * 제어 문자를 소스에 그대로 적지 않고 이름으로 쓴다. 눈에 보이지 않는 글자는 편집 한 번에
 * 조용히 사라지고, 그러면 이 시험은 통과하면서 아무것도 확인하지 않게 된다.
 */
class ClaudeLoginTranscriptTest {

    @Test
    fun `하이퍼링크로 감싸여 두 번 실려 온 주소를 한 번만 답한다`() {
        val transcript = "Opening browser to sign in…\n" +
            "If the browser didn't open, visit: " +
            hyperlinkTo(MEASURED_URL) +
            "\nPaste code here if prompted > "

        assertEquals(MEASURED_URL, authorizationUrlIn(transcript))
    }

    @Test
    fun `색 코드가 붙어 와도 주소만 답한다`() {
        // PTY 에 물려 재면 주소가 파란색으로 온다. 앱은 파이프로 부르지만, 이 갈래가 실제로
        // 있다는 것을 실측이 보여줬으므로 둘 다 읽을 수 있어야 한다.
        val transcript = "If the browser didn't open, visit: " +
            "${ESCAPE}[94m$MEASURED_URL${ESCAPE}[39m\n" +
            "Paste code here if prompted > "

        assertEquals(MEASURED_URL, authorizationUrlIn(transcript))
    }

    @Test
    fun `아직 아무것도 찍히지 않았으면 답하지 않는다`() {
        assertNull(authorizationUrlIn(""))
        assertNull(authorizationUrlIn("Opening browser to sign in…\n"))
    }

    @Test
    fun `주소가 아직 흘러오는 중이면 답하지 않는다`() {
        // 잘린 주소는 열리지 않는 주소다. 화면에 그것을 적어 두면 사용자는 붙여넣고 나서야
        // 무엇이 잘못됐는지 알게 되고, 그때는 이미 코드를 받을 자리를 잃은 뒤다.
        val stillArriving = "If the browser didn't open, visit: https://claude.com/cai/oauth/autho"

        assertNull(authorizationUrlIn(stillArriving))
    }

    @Test
    fun `하이퍼링크가 아직 닫히지 않았어도 잘린 주소를 답하지 않는다`() {
        // OSC 시퀀스가 끝나기 전에 읽으면 그 안의 주소가 그대로 노출된다. 그 주소는 BEL 로
        // 끝나는데, BEL 은 공백이 아니라 순진한 정규식에는 주소의 일부로 보인다.
        val halfWritten = "If the browser didn't open, visit: $ESCAPE]8;;$MEASURED_URL"

        assertNull(authorizationUrlIn(halfWritten))
    }

    @Test
    fun `주소에 제어 문자가 섞이지 않는다`() {
        val found = authorizationUrlIn(hyperlinkTo(MEASURED_URL) + " ")

        assertEquals(MEASURED_URL, found)
        assertFalse(found.orEmpty().any { it.isISOControl() }, "제어 문자가 주소에 섞였습니다: $found")
    }
}

/**
 * 실측이 받은 것과 같은 모양으로 감싼다: `ESC ] 8 ; ; <주소> BEL <주소> ESC ] 8 ; ; BEL`.
 *
 * 주소가 두 번 든 것이 이 모양의 요점이다 — 앞은 터미널이 링크로 걸 대상이고, 뒤는 링크를
 * 모르는 터미널에 보일 글자다.
 */
private fun hyperlinkTo(url: String) = "$ESCAPE]8;;$url$BELL$url$ESCAPE]8;;$BELL"

private const val ESCAPE = '\u001B'
private const val BELL = '\u0007'

/** 실측에서 받은 주소의 모양 그대로. 프로브가 만든 값이라 비밀이 아니다. */
private const val MEASURED_URL =
    "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
        "&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback" +
        "&scope=org%3Acreate_api_key+user%3Aprofile&code_challenge=JgZQ97rzHDbE2ZyvdPjsg4IUX6q-5TFfXBbhmcaKEDQ" +
        "&code_challenge_method=S256&state=CZDK7EMCJg2n_28egtTyvUEjGUNUD_uTWlMTTaK8lfM"
