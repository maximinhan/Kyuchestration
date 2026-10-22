package com.kyuchestration.desktop.workdir

import java.nio.file.Path

/**
 * 워크디렉토리 한 곳의 지금 상태를 알아 오는 자리.
 *
 * 설계 문서 5.1 의 3층 구조에서 표시 계층이 조율 계층에게 묻는 통로다. 화면과 상태 홀더는 이
 * 인터페이스만 알고, 그 뒤가 kyu 프로세스인지 다른 무엇인지는 모른다.
 *
 * fun interface 라 테스트는 람다 하나로 대신 세울 수 있다 — 가짜를 만들어 주는 라이브러리가
 * 필요 없다.
 */
fun interface WorkDirObserver {

    /**
     * @throws WorkDirObservationFailure 관찰이 성립하지 않았을 때.
     */
    fun observe(workDirPath: Path): WorkDirSnapshot
}
