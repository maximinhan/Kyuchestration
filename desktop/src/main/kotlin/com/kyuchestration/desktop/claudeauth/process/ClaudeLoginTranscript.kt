package com.kyuchestration.desktop.claudeauth.process

/**
 * `claude auth login` 이 지금까지 찍은 것에서 브라우저 주소를 찾는다. 아직 없으면 null.
 *
 * 순수 함수로 갈라 둔 이유는 여기가 이 흐름에서 유일하게 까다로운 판단이어서다. 프로세스를
 * 띄우지 않고도 전부 시험할 수 있고, 실제로 틀리기 쉬운 자리도 여기다.
 *
 * **찍히는 것의 모양**(chat-ui-design.md 7.3 가, 바이트로 실측):
 *
 * ```
 * If the browser didn't open, visit: ESC ] 8 ; ; <주소> BEL <주소> ESC ] 8 ; ; BEL
 * ```
 *
 * OSC 8 하이퍼링크라 **같은 주소가 두 번** 실려 온다 — 터미널이 링크로 걸 대상 하나와, 링크를
 * 모르는 터미널에 보일 글자 하나다. 시퀀스를 걷어내지 않고 이어 읽으면 주소가 두 번 붙은
 * 문자열이 되고, 그것을 브라우저에 넣으면 열리지 않는다.
 */
internal fun authorizationUrlIn(loginTranscript: String): String? {
    val readable = loginTranscript.withoutTerminalEscapes()
    val match = AUTHORIZATION_URL.find(readable) ?: return null

    // 아직 흘러오는 중일 수 있다. 찾은 것이 지금까지 받은 것의 끝에 걸쳐 있으면 주소가 잘려 있을
    // 수 있고, 잘린 주소는 열리지 않는 주소다 — 뒤에 무엇이든 한 글자가 더 온 뒤에 답한다.
    if (match.range.last == readable.lastIndex) {
        return null
    }
    return match.value
}

/**
 * 색과 하이퍼링크 시퀀스를 걷어낸다.
 *
 * 둘을 갈라 다룬다. OSC(`ESC ]` … BEL)는 **담긴 것째로** 지운다 — 그 안에 든 것이 위의 중복
 * 주소이고, 남겨 두면 두 개를 얻는다. CSI 색 코드(`ESC [` … 글자 하나)는 글자 사이에 끼어들 뿐이라
 * 시퀀스만 지우면 된다(PTY 로 재면 주소가 파란색으로 온다).
 *
 * 아직 끝나지 않은 시퀀스는 걷어내지 않는다. 그대로 두는 편이 맞다 — 그 안의 주소도 아직
 * 잘려 있을 것이고, 그 사실을 위의 끝자리 판정이 잡는다.
 */
private fun String.withoutTerminalEscapes(): String =
    replace(OPERATING_SYSTEM_COMMAND, "").replace(COLOR_CODE, "")

/**
 * 주소의 끝을 공백만이 아니라 제어 문자로도 가른다.
 *
 * `\s` 만으로는 부족하다. BEL(0x07)은 공백이 아니라서, 아직 끝나지 않은 OSC 안에서 주소를 잡으면
 * 그 뒤의 BEL 과 중복 주소까지 한 덩어리로 딸려 온다.
 */
private val AUTHORIZATION_URL = Regex("""https://[^\s\p{Cntrl}]+""")

/** `ESC ]` 로 시작해 BEL 또는 `ESC \` 로 끝나는 것 전부. */
private val OPERATING_SYSTEM_COMMAND = Regex("""\x1B\][^\x07\x1B]*(?:\x07|\x1B\\)""")

/** `ESC [` 로 시작해 글자 하나로 끝나는 CSI 시퀀스. 색이 이 모양으로 온다. */
private val COLOR_CODE = Regex("""\x1B\[[0-9;?]*[ -/]*[@-~]""")
