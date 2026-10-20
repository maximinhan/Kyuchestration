package com.kyuchestration.desktop.terminal.coordrecord

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `.coord/conversations.json` 의 `childSessions.main` 열쇠를 읽는다(session-tree-design.md 6.14).
 *
 * 엔진이 같은 파일을 읽는 규칙(conversations.go 의 readConversations)과 같은 선에서 갈린다 — 없는 파일과 읽을 수
 * 없는 문서는 "적힌 하위 없음" 이다. 엔진도 그 문서를 버리고 새로 적으므로, 그 안의 이름이 다음 이름을 막지 않는다.
 */
class CoordConversationRecordChildSessionIdsTest {

    private val workDirPath = createTempDirectory("kyu-record-test")

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        workDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `메인 아래 적힌 하위 id 들을 돌려준다`() {
        writeRecord(
            """
            {"schemaVersion":1,"conversations":{"main":"d1b9"},"delegations":{},
             "childSessions":{"main":{"child-1":{"conversationId":"5e0c","cwd":"proj-a","title":"proj-a","startedAt":"2026-10-20T10:01:12Z"},
                                      "child-2":{"conversationId":"77ab","cwd":"scratch-x","title":"scratch-x","startedAt":"2026-10-20T10:01:14Z"}},
                              "proj-a":{"child-9":{"conversationId":"0000","cwd":"x","title":"x","startedAt":"2026-10-20T10:01:14Z"}}}}
            """.trimIndent(),
        )

        assertEquals(setOf("child-1", "child-2"), CoordConversationRecordChildSessionIds().childSessionIdsRecordedUnderMain(workDirPath))
    }

    @Test
    fun `기록 파일이 없으면 적힌 하위가 없다`() {
        assertEquals(emptySet(), CoordConversationRecordChildSessionIds().childSessionIdsRecordedUnderMain(workDirPath))
    }

    @Test
    fun `하위 기록 자리가 없는 앞 판의 파일도 적힌 하위가 없다`() {
        writeRecord("""{"schemaVersion":1,"conversations":{"main":"d1b9"}}""")

        assertEquals(emptySet(), CoordConversationRecordChildSessionIds().childSessionIdsRecordedUnderMain(workDirPath))
    }

    @Test
    fun `읽을 수 없는 문서나 모르는 판은 엔진처럼 없는 기록으로 본다`() {
        writeRecord("이건 JSON 이 아니다")
        assertEquals(emptySet(), CoordConversationRecordChildSessionIds().childSessionIdsRecordedUnderMain(workDirPath))

        writeRecord("""{"schemaVersion":2,"childSessions":{"main":{"child-5":{}}}}""")
        assertEquals(emptySet(), CoordConversationRecordChildSessionIds().childSessionIdsRecordedUnderMain(workDirPath))
    }

    private fun writeRecord(content: String) {
        workDirPath.resolve(".coord").createDirectories().resolve("conversations.json").writeText(content)
    }
}
