package com.kyuchestration.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 진짜 대화상자를 띄우지 않는다. 여기서 시험하는 것은 "대화상자가 돌려준 값을 어떻게 읽는가"
 * 이고, 그 판단은 창 없이 전부 확인할 수 있다 — 창을 띄우는 쪽은 헤드리스 CI 에서 아예 돌지
 * 않으므로 판단을 그 안에 두면 시험할 방법이 사라진다.
 */
class WorkDirDirectoryChooserTest {

    @Test
    fun `들어가 있던 폴더의 이름이 한 번 더 붙었으면 그 폴더를 고른 것으로 읽는다`() {
        val meant = directoryTheFileChooserMeant(
            selectedPath = Path.of("/home/me/test/test"),
            currentDirectory = Path.of("/home/me/test"),
            isExistingDirectory = directoriesAt("/home/me", "/home/me/test"),
        )

        assertEquals(Path.of("/home/me/test"), meant)
    }

    @Test
    fun `있는 자리를 골랐으면 이름이 겹쳐도 그대로 둔다`() {
        val meant = directoryTheFileChooserMeant(
            selectedPath = Path.of("/home/me/proj/proj"),
            currentDirectory = Path.of("/home/me/proj"),
            isExistingDirectory = directoriesAt("/home/me/proj", "/home/me/proj/proj"),
        )

        // 부모와 같은 이름의 하위 디렉토리는 실제로 있을 수 있다(proj/proj). 고른 자리가 있으면
        // 그것이 사용자가 목록에서 짚은 자리이지 텍스트 칸에 남은 값이 아니다.
        assertEquals(Path.of("/home/me/proj/proj"), meant)
    }

    @Test
    fun `직접 적어 넣은 없는 이름은 보정하지 않는다`() {
        val meant = directoryTheFileChooserMeant(
            selectedPath = Path.of("/home/me/test/tset"),
            currentDirectory = Path.of("/home/me/test"),
            isExistingDirectory = directoriesAt("/home/me/test"),
        )

        // "없으면 부모" 로 넓히면 이 오타가 조용히 /home/me/test 를 여는 것으로 끝난다. 사용자가
        // 적은 이름은 자기가 적은 그대로 없다고 답해야 다시 적을 수 있다.
        assertEquals(Path.of("/home/me/test/tset"), meant)
    }

    @Test
    fun `현재 디렉토리 밖의 자리는 보정하지 않는다`() {
        val meant = directoryTheFileChooserMeant(
            selectedPath = Path.of("/mnt/other/test"),
            currentDirectory = Path.of("/home/me/test"),
            isExistingDirectory = directoriesAt("/home/me/test"),
        )

        // 텍스트 칸에 절대 경로를 적으면 현재 디렉토리와 상관없는 자리가 나온다. 이름 끝자락이
        // 우연히 같다고 해서 사용자가 적은 자리를 다른 자리로 바꿔서는 안 된다.
        assertEquals(Path.of("/mnt/other/test"), meant)
    }

    @Test
    fun `되돌릴 자리마저 없으면 고른 값을 그대로 답한다`() {
        val meant = directoryTheFileChooserMeant(
            selectedPath = Path.of("/home/me/test/test"),
            currentDirectory = Path.of("/home/me/test"),
            isExistingDirectory = directoriesAt("/home/me"),
        )

        // 대화상자가 떠 있는 동안 그 자리가 밖에서 지워진 경우다. 없는 자리를 다른 없는 자리로
        // 바꿔 봐야 여는 쪽의 실패 문구만 엉뚱해진다.
        assertEquals(Path.of("/home/me/test/test"), meant)
    }

    private fun directoriesAt(vararg paths: String): (Path) -> Boolean {
        val directories = paths.map(Path::of).toSet()
        return { it in directories }
    }
}
