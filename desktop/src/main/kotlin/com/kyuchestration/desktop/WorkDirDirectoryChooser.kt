package com.kyuchestration.desktop

import java.awt.Window
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlin.io.path.isDirectory

/**
 * 워크디렉토리로 삼을 디렉토리를 고르는 시스템 대화상자를 띄운다. 고르지 않고 닫으면 null.
 *
 * 새 자리를 만드는 길도 여기 있다. 대화상자의 새 폴더 버튼이 하는 일이라, 앱이 만들 자리와
 * 이름을 따로 묻는 폼을 들 이유가 없다.
 *
 * Compose 의 FileDialog(AWT) 대신 Swing 의 JFileChooser 를 쓴다. AWT 쪽은 디렉토리를 고르는
 * 방법이 플랫폼마다 다르고(맥은 시스템 속성, 윈도우는 아예 불가) JFileChooser 만 세 플랫폼에서
 * 같은 방식으로 디렉토리를 고른다.
 *
 * 모달 대화상자는 자기 이벤트 루프를 돌린다. Compose Desktop 도 같은 AWT 이벤트 스레드에서
 * 그리므로, 대화상자가 떠 있는 동안에도 뒤의 창은 계속 그려진다.
 */
fun chooseWorkDirDirectory(ownerWindow: Window?): Path? {
    val chooser = JFileChooser(File(System.getProperty("user.home"))).apply {
        dialogTitle = "워크디렉토리 선택"
        // 워크디렉토리는 디렉토리다. 파일까지 고를 수 있게 두면 고른 것이 무엇인지 매번 되물어야 한다.
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }

    if (chooser.showDialog(ownerWindow, "열기") != JFileChooser.APPROVE_OPTION) {
        return null
    }

    val selectedFile = chooser.selectedFile ?: return null
    return directoryTheFileChooserMeant(
        selectedPath = selectedFile.toPath(),
        currentDirectory = chooser.currentDirectory.toPath(),
    )
}

/**
 * JFileChooser 가 돌려준 값에서 사용자가 실제로 고른 디렉토리를 읽어 낸다.
 *
 * **왜 그대로 쓰지 못하는가.** 디렉토리 전용 모드에서 목록의 폴더를 더블클릭해 그 안으로 들어가면,
 * 대화상자는 그 폴더를 현재 디렉토리로 삼으면서도 파일 이름 칸에 방금 짚은 이름을 남겨 둔다.
 * 그 상태로 [열기] 를 누르면 승인 동작이 이름 칸의 값을 현재 디렉토리 기준으로 풀기 때문에
 * (BasicFileChooserUI.ApproveSelectionAction), `~/test` 안에서 누른 것이 `~/test/test` 로 나온다.
 * 빈 폴더를 만들어 그 안까지 들어간 사람이 늘 밟는 길이고, 그때 앱은 없는 자리를 열려다 실패한다.
 *
 * 보정 조건을 그 기전 그대로 좁힌다 — 고른 자리가 없고 · 그 부모가 지금 들어와 있는 디렉토리이고 ·
 * 마지막 이름이 그 디렉토리의 이름과 같을 때만 되돌린다. "없으면 부모" 로 넓히지 않는 이유는
 * 사용자가 이름 칸에 직접 적어 넣은 값도 같은 길로 오기 때문이다. 오타 하나가 조용히 다른
 * 디렉토리를 여는 것으로 끝나면, 자기가 무엇을 열었는지는 아무 데도 뜨지 않는다.
 *
 * 부모와 같은 이름의 하위 디렉토리(`proj/proj`)는 실제로 있을 수 있다. 그 자리는 존재 확인에서
 * 갈린다 — 있으면 사용자가 목록에서 짚은 자리이지 이름 칸에 남은 값이 아니다.
 *
 * @param isExistingDirectory 그 자리에 디렉토리가 있는지 답하는 자리. 검사가 진짜 파일 시스템을
 *   세우지 않고 판단만 확인하기 위한 이음매다.
 */
internal fun directoryTheFileChooserMeant(
    selectedPath: Path,
    currentDirectory: Path,
    isExistingDirectory: (Path) -> Boolean = Path::isDirectory,
): Path {
    val currentDirectoryNameRepeated = !isExistingDirectory(selectedPath) &&
        selectedPath.parent == currentDirectory &&
        selectedPath.fileName == currentDirectory.fileName &&
        isExistingDirectory(currentDirectory)

    return if (currentDirectoryNameRepeated) currentDirectory else selectedPath
}
