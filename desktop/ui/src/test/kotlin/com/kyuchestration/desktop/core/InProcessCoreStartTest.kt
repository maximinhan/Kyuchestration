package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.mirror.CoreMirror
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 코어를 이 JVM 의 자기 스레드에 세우는 자리. 세우다 실패하면 화면이 그것을 봐야 한다 — 거울은 첫 스냅샷을 기다리며 서므로,
 * 코어 쪽 줄이 닫히지 않으면 화면은 창 하나 없이 영원히 기다린다.
 */
class InProcessCoreStartTest {

    @Test
    fun `코어를 조립하다 실패하면 화면 쪽 줄이 그 실패로 닫혀 거울을 세우던 자리가 같은 실패를 받는다`() {
        val framesToCore = Channel<String>(Channel.UNLIMITED)
        val framesToScreen = Channel<String>(Channel.UNLIMITED)
        val recordedEntries = CopyOnWriteArrayList<DiagnosticLogEntry>()

        startCoreInThisProcess(
            framesFromScreen = framesToCore,
            framesToScreen = framesToScreen,
            coreIdentity = TEST_CORE_IDENTITY,
            diagnosticLog = object : DiagnosticLog {
                override fun record(entry: DiagnosticLogEntry) {
                    recordedEntries += entry
                }
            },
            assembleCore = { error("홀더를 세우다 실패했다") },
        )

        val failure = runBlocking {
            withTimeout(10_000) {
                assertFailsWith<IllegalStateException> {
                    CoreMirror.connect(framesToScreen, framesToCore, CoroutineScope(Dispatchers.Default), DiagnosticLog.Discarding, TEST_SCREEN_VERSION_LABEL)
                }
            }
        }
        assertEquals("홀더를 세우다 실패했다", failure.message)
        val recorded = recordedEntries.filterIsInstance<DiagnosticLogEntry.UnhandledFailure>().single()
        assertEquals("홀더를 세우다 실패했다", recorded.failure.message)
    }
}
