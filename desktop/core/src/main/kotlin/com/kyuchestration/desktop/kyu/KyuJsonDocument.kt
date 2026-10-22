package com.kyuchestration.desktop.kyu

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * kyu 가 `--json` 으로 낸 문서 한 벌을 읽는다. 판을 먼저 보고, 아는 판일 때만 문서를 읽는다.
 *
 * 판을 먼저 보는 순서가 이 함수의 전부다. 문서 전체를 먼저 읽으면 판이 올라가며 사라진 필드가
 * "읽을 수 없는 출력" 으로 보고돼 진짜 이유(판이 다르다)가 가려진다.
 *
 * 판은 명령마다 따로 센다(json_output.go 의 판 규약). 그래서 아는 판을 인자로 받는다 —
 * 이 함수가 아는 수를 하나 들고 있으면 한 명령의 판이 오르는 날 다른 명령까지 멈춘다.
 *
 * 관찰 어댑터 안에 있던 규칙이다. 같은 규칙을 지켜야 하는 문서가 다섯이 되면서
 * (list · auth add · auth list · repos owners · repos list · clone) 여기로 나왔다.
 */
internal fun <T> readKyuJsonDocument(
    rawJson: String,
    supportedSchemaVersion: Int,
    documentDeserializer: DeserializationStrategy<T>,
): T {
    try {
        val root = kyuJson.parseToJsonElement(rawJson)

        val schemaVersion = kyuJson.decodeFromJsonElement(KyuJsonSchemaProbe.serializer(), root).schemaVersion
        if (schemaVersion != supportedSchemaVersion) {
            throw KyuDocumentFailure.UnsupportedSchemaVersion(schemaVersion, supportedSchemaVersion)
        }

        return kyuJson.decodeFromJsonElement(documentDeserializer, root)
    } catch (failure: SerializationException) {
        throw KyuDocumentFailure.Unreadable(failure)
    }
}

/**
 * 문서를 읽지 못한 이유.
 *
 * 실행기가 KyuCommandFailure 로 하는 것과 같다 — 사실만 던지고, 사람에게 할 말은 부르는 쪽이
 * 자기 실패 타입에서 정한다. "판이 다르다" 는 사실은 어느 명령에서나 같지만, 목록을 보려던
 * 사람과 레포를 고르던 사람에게 할 말은 같지 않다.
 */
internal sealed class KyuDocumentFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class UnsupportedSchemaVersion(
        val actualSchemaVersion: Int,
        val supportedSchemaVersion: Int,
    ) : KyuDocumentFailure("kyu 가 낸 문서는 판 $actualSchemaVersion 인데 이 앱은 판 $supportedSchemaVersion 만 읽습니다.")

    /**
     * @param parseFailure 읽다가 받은 예외. 부르는 쪽이 자기 안내 문구에 원인을 실을 수 있도록
     *   따로 들고 있는다 — Throwable.cause 로만 두면 널 가능 타입이라 쓰는 자리마다 없는 경우를
     *   다시 따져야 한다.
     */
    class Unreadable(val parseFailure: Throwable) : KyuDocumentFailure("kyu 의 출력을 읽지 못했습니다.", parseFailure)
}

private val kyuJson = Json {
    // 판을 올리지 않고 필드가 느는 것이 계약이 허용한 변경이다. 모르는 필드에서 멈추면
    // kyu 가 한 줄 더 내보내는 순간 GUI 가 통째로 죽는다.
    ignoreUnknownKeys = true
}

/**
 * 판만 떼어 읽기 위한 최소한의 모양.
 */
@Serializable
private data class KyuJsonSchemaProbe(val schemaVersion: Int)
