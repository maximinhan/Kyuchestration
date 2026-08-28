package com.kyuchestration.desktop.terminal.pty

import com.jediterm.core.util.TermSize
import com.kyuchestration.desktop.terminal.SessionTarget
import com.pty4j.PtyProcessBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 진짜 PTY 하나를 열어 커넥터가 읽고·쓰고·크기를 옮기는지 본다.
 *
 * JediTermWidget 은 세우지 않는다. 그쪽은 Swing 이라 화면 없는 곳에서는 폰트 계측부터 걸리고,
 * 여기서 확인하려는 것은 화면이 아니라 그 아래의 통로다. 화면이 정말 뜨는지는 사람이 창을 열어 본다.
 *
 * 붙는 상대도 kyu 가 아니라 sh 다. PTY 가 이 환경에서 실제로 열리는지, 우리가 재정의한 resize 가
 * 자식에게 닿는지를 보는 데에 세션이나 tmux 는 필요 없다.
 */
class KyuAttachTtyConnectorTest {

    @Test
    fun `PTY 안에서 실행한 명령의 출력을 읽어 온다`() {
        val connector = shellInPty()

        connector.write("echo 뀨케스트레이션-확인; exit\n")
        val output = connector.readUntilClosed()

        // 무엇이 나왔는지를 먼저 본다. 종료 코드가 앞에 있으면 셸이 우리가 쓴 것과 다른 줄을
        // 실행했을 때 "0 이 아니다" 만 남고, 정작 원인인 그 줄이 어디에도 찍히지 않는다.
        assertTrue(
            "뀨케스트레이션-확인" in output,
            "PTY 가 낸 출력에 echo 결과가 있어야 한다. 실제로 읽은 것: $output",
        )
        assertEquals(0, connector.waitFor(), "셸이 정상으로 끝나야 한다. PTY 가 낸 것: $output")
    }

    @Test
    fun `크기를 바꾸면 PTY 안의 프로그램이 그 크기를 본다`() {
        val connector = shellInPty()

        // 화면이 자리를 잡을 때 JediTerm 이 부르는 것이 바로 이 메서드다. 여기가 끊기면 창만
        // 커지고 세션 안의 화면은 처음 크기에 갇힌다.
        connector.resize(TermSize(120, 40))

        connector.write("stty size; exit\n")
        val output = connector.readUntilClosed()

        // stty 는 "행 열" 순서로 답한다.
        assertTrue(
            "40 120" in output,
            "PTY 안의 stty 가 40행 120열을 보고해야 한다. 실제로 읽은 것: $output",
        )
    }

    private fun shellInPty(): KyuAttachTtyConnector {
        val ptyProcess = PtyProcessBuilder(arrayOf("sh"))
            .setEnvironment(
                mapOf(
                    "TERM" to "xterm-256color",
                    "PATH" to System.getenv("PATH"),
                    // 터미널을 여는 쪽은 자식에게 두 가지를 말해 줘야 한다 — 어떤 화면인가(TERM)와
                    // 어떤 글자인가(LANG). 커넥터는 자기 쪽 인코딩을 UTF-8 로 못 박아 두는데
                    // (KyuAttachTtyConnector 가 ProcessTtyConnector 에 넘기는 Charsets.UTF_8),
                    // 자식에게 같은 말을 하지 않으면 맥의 /bin/sh(bash 3.2)가 C 로케일로 뜬다.
                    // 그 readline 은 8 비트 바이트를 글자가 아니라 Meta 키 조합으로 읽어서, 한글
                    // 한 글자가 편집 명령 여럿이 된다 — 우리가 쓴 줄 대신 히스토리에서 끌려 나온
                    // 다른 줄이 실행되고, 셸은 그 줄의 결과(127)로 끝났다.
                    // 리눅스의 /bin/sh(dash)는 줄 편집기 자체가 없어 이 구멍이 보이지 않았다.
                    "LANG" to "en_US.UTF-8",
                ),
            )
            .setInitialColumns(80)
            .setInitialRows(24)
            .start()

        return KyuAttachTtyConnector(ptyProcess, SessionTarget.Repo("proj-a"))
    }

    /**
     * 상대가 끊을 때까지 읽는다.
     *
     * PTY 는 파이프와 달리 자식이 끝나도 읽기가 곧바로 EOF 가 되지 않고 I/O 오류로 끊기기도 한다.
     * 이 시험이 보려는 것은 "무엇이 나왔는가" 라, 끊긴 방식은 여기서 가리지 않는다.
     */
    private fun KyuAttachTtyConnector.readUntilClosed(): String {
        val output = StringBuilder()
        val buffer = CharArray(1024)
        while (true) {
            val readCount = try {
                read(buffer, 0, buffer.size)
            } catch (_: java.io.IOException) {
                break
            }
            if (readCount < 0) {
                break
            }
            output.appendRange(buffer, 0, readCount)
        }
        return output.toString()
    }
}
