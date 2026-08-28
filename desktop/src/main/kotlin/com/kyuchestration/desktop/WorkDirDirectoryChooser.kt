package com.kyuchestration.desktop

import java.awt.Window
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * 열 워크디렉토리를 고르는 시스템 대화상자를 띄운다. 고르지 않고 닫으면 null.
 */
fun chooseWorkDirDirectory(ownerWindow: Window?): Path? =
    chooseDirectory(ownerWindow, dialogTitle = "워크디렉토리 선택", approveButtonText = "열기")

/**
 * 새 워크디렉토리를 만들 부모 디렉토리를 고른다. 고르지 않고 닫으면 null.
 *
 * 여는 쪽과 문구를 갈라 둔다. 같은 대화상자라도 사용자가 고르는 것이 "그 워크디렉토리" 와
 * "그 아래에 만들 자리" 로 다르고, 버튼에 "열기" 라고 적혀 있으면 고른 디렉토리가 열릴 것처럼 읽힌다.
 */
fun chooseNewWorkDirParentDirectory(ownerWindow: Window?): Path? =
    chooseDirectory(ownerWindow, dialogTitle = "새 워크디렉토리를 만들 자리 선택", approveButtonText = "여기에 만들기")

/**
 * Compose 의 FileDialog(AWT) 대신 Swing 의 JFileChooser 를 쓴다. AWT 쪽은 디렉토리를 고르는
 * 방법이 플랫폼마다 다르고(맥은 시스템 속성, 윈도우는 아예 불가) JFileChooser 만 세 플랫폼에서
 * 같은 방식으로 디렉토리를 고른다.
 *
 * 모달 대화상자는 자기 이벤트 루프를 돌린다. Compose Desktop 도 같은 AWT 이벤트 스레드에서
 * 그리므로, 대화상자가 떠 있는 동안에도 뒤의 창은 계속 그려진다.
 */
private fun chooseDirectory(ownerWindow: Window?, dialogTitle: String, approveButtonText: String): Path? {
    val chooser = JFileChooser(File(System.getProperty("user.home"))).apply {
        this.dialogTitle = dialogTitle
        // 워크디렉토리는 디렉토리다. 파일까지 고를 수 있게 두면 고른 것이 무엇인지 매번 되물어야 한다.
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = false
    }

    if (chooser.showDialog(ownerWindow, approveButtonText) != JFileChooser.APPROVE_OPTION) {
        return null
    }
    return chooser.selectedFile?.toPath()
}
