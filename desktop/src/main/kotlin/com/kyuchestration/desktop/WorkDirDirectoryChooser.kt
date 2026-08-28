package com.kyuchestration.desktop

import java.awt.Window
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * 워크디렉토리를 고르는 시스템 대화상자를 띄운다. 고르지 않고 닫으면 null.
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
    return chooser.selectedFile?.toPath()
}
