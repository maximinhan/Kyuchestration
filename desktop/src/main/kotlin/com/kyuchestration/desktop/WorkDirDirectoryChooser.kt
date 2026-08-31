package com.kyuchestration.desktop

import com.kyuchestration.desktop.platform.runningOnMac
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlin.io.path.isDirectory

/**
 * 워크디렉토리로 삼을 디렉토리를 고르는 시스템 대화상자를 띄운다. 고르지 않고 닫으면 null.
 *
 * 새 자리를 만드는 길도 여기 있다. 두 대화상자 모두 새 폴더를 만들 수 있어서, 앱이 만들 자리와
 * 이름을 따로 묻는 폼을 들 이유가 없다.
 *
 * **맥과 리눅스가 서로 다른 대화상자를 쓴다.** 처음에는 JFileChooser 하나로 두었다 — AWT 의
 * FileDialog 는 디렉토리를 고르는 방법이 플랫폼마다 다르고(맥은 시스템 속성, 윈도우는 아예 불가)
 * JFileChooser 만 세 플랫폼에서 같은 방식으로 디렉토리를 고르기 때문이다. 그 근거가 지금은
 * 약하다. 윈도우 네이티브는 이 앱의 대상이 아니고(릴리스가 윈도우 엔진 바이너리를 내지 않는다 —
 * 윈도우 사용자는 WSL 안에서 리눅스 앱을 쓴다), 그러면 갈라지는 것은 맥과 리눅스 둘뿐이라
 * "아예 불가" 인 자리가 없다.
 *
 * 맥에서 네이티브 창을 쓰는 값은 둘이다. 하나는 그 창이 맥 사용자가 다른 앱에서 늘 보는 창이라는
 * 것(사이드바 · 최근 자리 · ⌘⇧G)이고, 다른 하나는 아래 [directoryTheFileChooserMeant] 가 되돌리는
 * 그 어긋남이 애초에 없다는 것이다.
 *
 * 모달 대화상자는 자기 이벤트 루프를 돌린다. Compose Desktop 도 같은 AWT 이벤트 스레드에서
 * 그리므로, 대화상자가 떠 있는 동안에도 뒤의 창은 계속 그려진다.
 */
fun chooseWorkDirDirectory(ownerWindow: Window?): Path? =
    if (runningOnMac()) {
        chooseDirectoryWithMacNativeDialog(ownerWindow)
    } else {
        chooseDirectoryWithFileChooser(ownerWindow)
    }

/**
 * 맥의 네이티브 폴더 선택창(NSOpenPanel)을 띄운다.
 *
 * AWT 의 FileDialog 는 기본적으로 파일을 고르는 창이다. 맥 툴킷만 이 시스템 속성을 보고 그 창을
 * 디렉토리를 고르는 창으로 바꾼다 — 다른 플랫폼에서는 아무 뜻이 없어서, 이 갈래가 맥에만 있는
 * 이유가 그대로 여기다.
 *
 * 속성은 창을 닫자마자 되돌린다 — 아래 finally 가 그 자리다.
 */
private fun chooseDirectoryWithMacNativeDialog(ownerWindow: Window?): Path? {
    val settingBeforeDialog = System.getProperty(MAC_FILE_DIALOG_FOR_DIRECTORIES_PROPERTY)
    System.setProperty(MAC_FILE_DIALOG_FOR_DIRECTORIES_PROPERTY, "true")

    try {
        // FileDialog 의 주인은 Frame 또는 Dialog 다. 앱의 창은 Frame 이고, 주인이 없으면 AWT 가
        // 숨은 프레임을 대신 쓴다 — 그때는 창이 앱과 따로 뜬다.
        val dialog = FileDialog(ownerWindow as? Frame, "워크디렉토리 선택", FileDialog.LOAD)
        dialog.directory = System.getProperty("user.home")
        dialog.isMultipleMode = false
        // FileDialog 는 언제나 모달이라 이 줄이 창을 닫을 때까지 돌아오지 않는다.
        dialog.isVisible = true

        // 고르지 않고 닫으면 둘 다 비어 있다. 디렉토리를 고르는 창에서는 file 이 고른 디렉토리의
        // 이름이고 directory 가 그 자리의 부모라, 둘을 이어야 고른 자리가 된다.
        val parentDirectory = dialog.directory ?: return null
        val chosenDirectoryName = dialog.file ?: return null
        return Path.of(parentDirectory, chosenDirectoryName)
    } finally {
        // 창을 만들다 끊긴 길에서도 되돌린다. 프로세스 전체가 함께 보는 값이라, 켜 둔 채로 두면
        // 나중에 이 앱이 다른 목적으로 여는 FileDialog 까지 디렉토리만 고르게 된다.
        restoreMacFileDialogSetting(settingBeforeDialog)
    }
}

private fun restoreMacFileDialogSetting(settingBeforeDialog: String?) {
    if (settingBeforeDialog == null) {
        System.clearProperty(MAC_FILE_DIALOG_FOR_DIRECTORIES_PROPERTY)
    } else {
        System.setProperty(MAC_FILE_DIALOG_FOR_DIRECTORIES_PROPERTY, settingBeforeDialog)
    }
}

private fun chooseDirectoryWithFileChooser(ownerWindow: Window?): Path? {
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

/**
 * 맥 툴킷이 FileDialog 를 디렉토리 선택창으로 바꿀 때 보는 속성.
 *
 * 애플의 자바 시절부터 이어진 이름이라 apple 로 시작한다(JDK 의 CFileDialog 가 이 값을 읽어
 * NSOpenPanel 의 canChooseDirectories 로 넘긴다).
 */
private const val MAC_FILE_DIALOG_FOR_DIRECTORIES_PROPERTY = "apple.awt.fileDialogForDirectories"
