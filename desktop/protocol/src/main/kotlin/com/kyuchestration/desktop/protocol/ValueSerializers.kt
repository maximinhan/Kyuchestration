package com.kyuchestration.desktop.protocol

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** 시각은 ISO-8601 문자열로 건넌다(core-ui-split-design.md 6.3). 숫자로 두면 사람이 틀을 읽을 때 무엇인지 모른다. */
object InstantAsIsoStringSerializer : KSerializer<Instant> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * kyu 가 낱말로 답한 값(레포 상태 · 토큰 자리 · 계정 종류 · 클론 결과)을 그 낱말 그대로 건넨다.
 *
 * 이 넷은 모르는 낱말을 `Unrecognized` 로 들고 있는다(RepoState 의 머리말). 갈래 이름으로 건네면 그 갈래만 낱말과 다른
 * 모양이 되고, 엔진이 더한 새 낱말이 코어를 지나 화면에 닿는 길이 엔진 → 코어와 다르게 갈린다. 낱말 그대로면 받는
 * 쪽이 엔진 문서를 읽을 때와 같은 함수(`ofRawValue`)로 푼다.
 */
abstract class RawValueSerializer<T : Any>(
    serialName: String,
    private val rawValueOf: (T) -> String,
    private val valueOfRawValue: (String) -> T,
) : KSerializer<T> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(rawValueOf(value))

    override fun deserialize(decoder: Decoder): T = valueOfRawValue(decoder.decodeString())
}
