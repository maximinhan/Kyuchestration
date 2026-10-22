package com.kyuchestration.desktop.terminal

import kotlinx.serialization.Serializable

/**
 * 사람에게 보일 관문의 물음(`approvalRequired`).
 *
 * 승인은 자리가 아니라 [fingerprint] 에 대한 것이다 — 사람이 본 뒤 파일이 바뀌면 엔진이 지금 내용으로 다시 묻는다.
 */
@Serializable
data class ChildSessionGate(
    val relativeCwd: String,
    val fingerprint: String,
    val executionConfigs: List<GatedExecutionConfig>,
)

/** 걸린 설정 파일 하나. 무엇을 승인하는지 모르는 승인은 관문이 아니라 내용을 통째로 든다. */
@Serializable
data class GatedExecutionConfig(
    val relativePath: String,
    val absolutePath: String,
    val content: String,
)
