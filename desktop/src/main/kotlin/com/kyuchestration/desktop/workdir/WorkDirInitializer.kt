package com.kyuchestration.desktop.workdir

import java.nio.file.Path

/**
 * 워크디렉토리를 만들고 조율할 수 있는 상태로 만드는 자리.
 *
 * 관찰 포트와 갈라 둔다. 관찰은 3 초마다 저절로 일어나는 읽기이고 이쪽은 사람이 눌러야만
 * 일어나는 쓰기다 — 한 인터페이스에 묶으면 "저절로 도는 것" 과 "사람이 시키는 것" 이 같은
 * 이름 아래에 놓인다.
 *
 * 만드는 일과 제자리 초기화를 한 포트에 둔 것은 둘이 같은 명령(kyu init)이기 때문이다. 앱에서
 * 새로 만들 때와, 아직 초기화하지 않은 디렉토리를 열었을 때가 다를 이유가 없다.
 */
interface WorkDirInitializer {

    /**
     * [parentDirectory] 아래에 [newWorkDirName] 이름의 워크디렉토리를 만들고 초기화한다.
     */
    fun createWorkDir(parentDirectory: Path, newWorkDirName: String): WorkDirInitializationResult

    /**
     * 이미 있는 디렉토리를 그 자리에서 초기화한다.
     */
    fun initializeExistingWorkDir(workDirPath: Path): WorkDirInitializationResult
}

/**
 * 초기화를 시킨 결과.
 *
 * 실패를 예외가 아니라 갈래로 둔다. 여기서 실패는 "앱이 고장났다" 가 아니라 사람이 자주 만나는
 * 정상적인 답이다 — 이미 계획이 있는 자리를 다시 초기화하려 하면 kyu 는 덮어쓰기를 거절한다.
 * 화면이 그 답을 그대로 보여주면 되는 일에 예외를 쓰면, 부르는 쪽마다 try 로 감싸야 한다.
 */
sealed interface WorkDirInitializationResult {

    /** @param workDirPath 초기화가 끝난 워크디렉토리. 화면은 이 자리를 곧바로 연다. */
    data class Initialized(val workDirPath: Path) : WorkDirInitializationResult

    /**
     * 초기화가 일어나지 않았다.
     *
     * @param reason 화면이 그대로 보여줄 사람 말. kyu 가 stderr 로 남긴 문구이거나, kyu 를
     *   부르지도 못한 이유다. 종료 코드를 따로 들고 있지 않은 것은 그 숫자가 사용자가 할 일을
     *   바꾸지 않기 때문이다 — 어느 숫자든 화면이 하는 일은 이 문구를 보여주는 것 하나다.
     */
    data class NotInitialized(val reason: String) : WorkDirInitializationResult
}
