package com.kyuchestration.desktop.terminal.coordrecord

import com.kyuchestration.desktop.terminal.RecordedChildSessionIds
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 워크디렉토리의 대화 기록(`.coord/conversations.json`)에서 메인 아래 하위 id 들을 읽는다.
 *
 * **읽기만 한다.** 그 파일을 쓰는 것은 엔진 하나다(원칙 11) — 앱은 다음 하위 이름을 정할 때 이미 쓰인 이름을
 * 피하려고 열쇠만 본다. 필드 하나 깊이까지만 읽는 것도 그 때문이다: 대화 ID · cwd 같은 값의 뜻은 엔진의 것이다.
 *
 * **엔진이 읽는 규칙을 따른다**(conversations.go 의 readConversations). 파일이 없으면 기록이 없고, 문서가
 * 깨졌거나 모르는 판이면 엔진도 그것을 버리고 새로 적으므로 그 안의 이름은 다음 이름을 막지 않는다.
 * 파일이 있는데 읽지 못한 것(권한 등)만 실패다 — 그때는 엔진도 멈춘다.
 */
class CoordConversationRecordChildSessionIds : RecordedChildSessionIds {

    override fun childSessionIdsRecordedUnderMain(workDirPath: Path): Set<String> {
        val recordContent = try {
            workDirPath.resolve(COORD_DIRECTORY_NAME).resolve(CONVERSATIONS_FILE_NAME).readText()
        } catch (absent: NoSuchFileException) {
            return emptySet()
        }

        val record = try {
            Json.parseToJsonElement(recordContent) as? JsonObject
        } catch (unreadable: SerializationException) {
            null
        } ?: return emptySet()

        if ((record["schemaVersion"] as? JsonPrimitive)?.intOrNull != CONVERSATIONS_FILE_SCHEMA_VERSION) {
            return emptySet()
        }

        val childSessions = record["childSessions"] as? JsonObject ?: return emptySet()
        val childrenOfMain = childSessions[MAIN_PARENT_LABEL] as? JsonObject ?: return emptySet()
        return childrenOfMain.keys
    }
}

private const val COORD_DIRECTORY_NAME = ".coord"

/** conversations.go 의 conversationsFileName. */
private const val CONVERSATIONS_FILE_NAME = "conversations.json"

/** conversations.go 의 conversationsFileSchemaVersion. 판이 오르면 열쇠의 뜻도 달라졌을 수 있다. */
private const val CONVERSATIONS_FILE_SCHEMA_VERSION = 1

/** 하위의 상위 라벨(session_command_child.go 의 childSessionParentLabel). v1 의 상위는 메인 하나다. */
private const val MAIN_PARENT_LABEL = "main"
