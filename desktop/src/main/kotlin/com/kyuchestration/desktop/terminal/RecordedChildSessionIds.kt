package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 이 워크디렉토리의 기록에 메인 아래 이미 적힌 하위 세션 id 들을 묻는 자리.
 *
 * **앱이 하위의 이름을 정하는데(원칙 16) 엔진은 같은 상위 아래 같은 이름을 다시 받지 않는다**
 * (conversations.go 의 StartChildSessionConversation). 앱의 메모리는 앱을 켤 때마다 비지만 기록은 남으므로,
 * 다음 이름을 정하려면 기록에 무엇이 있는지 알아야 한다(nextChildSessionId).
 *
 * `fun interface` 라 상태 홀더 시험은 람다 하나로 세운다.
 */
fun interface RecordedChildSessionIds {

    /**
     * @throws java.io.IOException 기록 파일이 있는데 읽지 못했을 때. 엔진도 같은 자리에서 멈추므로
     *   (readConversations 의 읽기 실패 갈래) 그 하위는 띄울 수 없다.
     */
    fun childSessionIdsRecordedUnderMain(workDirPath: Path): Set<String>
}
