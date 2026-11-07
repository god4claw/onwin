package com.example.onwin.ui.notification

import android.Manifest
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.MainActivity
import com.example.onwin.OnWinApp
import com.example.onwin.data.audio.WavWriter
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.RecordingPhase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real app container and Android service; no foreground/capture test doubles. */
@RunWith(AndroidJUnit4::class)
class ProcessingServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as OnWinApp).container

    @Test
    fun importUsesRealProcessingForegroundService(): Unit = runBlocking {
        val source = File(context.cacheDir, "service-test-${UUID.randomUUID()}.wav")
        WavWriter(source).use { it.writeShorts(ShortArray(16_000) { 100 }) }
        var id: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(10_000) { container.processingController.state.first { !it.busy } }
                scenario.onActivity { id = container.processingController.enqueueImport(Uri.fromFile(source).toString()) }
                assertDone(checkNotNull(id), SessionSource.IMPORT)
            }
        } finally {
            id?.let { cleanup(it) }
            source.delete()
        }
    }

    @Test
    fun microphoneStopsFromNotificationWhileBackgroundedAndSwitchesToProcessing(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        var id: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(10_000) { container.processingController.state.first { !it.busy } }
                scenario.onActivity { id = container.processingController.startRecording() }
                val sessionId = checkNotNull(id)
                withTimeout(15_000) {
                    container.processingController.state.first {
                        it.recording?.sessionId == sessionId && it.recording.phase == RecordingPhase.RECORDING
                    }
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                delay(750)
                // The same immutable service PendingIntent used by the notification's Stop action.
                PendingIntent.getService(context, 200,
                    ProcessingService.stopRecordingIntent(context, sessionId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ).send()
                assertDone(sessionId, SessionSource.MIC)
            }
        } finally { id?.let { cleanup(it) } }
    }

    @Test
    fun microphoneStartsDuringProcessingAndImportWaitsForItsRecording(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val source = File(context.cacheDir, "service-queue-${UUID.randomUUID()}.wav")
        WavWriter(source).use { it.writeShorts(ShortArray(16_000 * 20) { 100 }) }
        val uri = Uri.fromFile(source).toString()
        val controller = container.processingController
        var first: String? = null
        var mic: String? = null
        var last: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(10_000) { controller.state.first { !it.busy } }
                scenario.onActivity { first = controller.enqueueImport(uri) }
                val firstId = checkNotNull(first)
                withTimeout(10_000) { controller.state.first { it.running?.sessionId == firstId } }
                scenario.onActivity {
                    mic = controller.startRecording()
                    last = controller.enqueueImport(uri)
                }
                val micId = checkNotNull(mic)
                val lastId = checkNotNull(last)
                withTimeout(10_000) {
                    controller.state.first {
                        it.running?.sessionId == firstId && it.recording?.sessionId == micId &&
                            it.recording.phase == RecordingPhase.RECORDING
                    }
                }
                withTimeout(15_000) {
                    container.sessionRepository.observeSession(firstId).first { it?.session?.status == SessionStatus.DONE }
                }
                assertEquals(SessionStatus.QUEUED, container.sessionRepository.get(lastId)?.status)
                controller.stopRecording(micId)
                assertDone(micId, SessionSource.MIC)
                assertDone(lastId, SessionSource.IMPORT)
            }
        } finally {
            val ids = listOfNotNull(first, mic, last)
            ids.forEach(controller::cancel)
            ids.forEach { cleanup(it) }
            source.delete()
        }
    }

    private suspend fun assertDone(id: String, source: SessionSource) {
        val session = withTimeout(30_000) {
            container.sessionRepository.observeSession(id).first {
                it?.session?.status in setOf(SessionStatus.DONE, SessionStatus.FAILED, SessionStatus.CANCELLED)
            }
        }!!.session
        assertEquals("${session.errorCode}: ${session.errorMessage}", SessionStatus.DONE, session.status)
        assertEquals(source, session.source)
        assertTrue(checkNotNull(session.durationMs) > 0)
        assertTrue(container.sessionRepository.wavFile(checkNotNull(session.wavFileName)).length() > 44)
        withTimeout(10_000) { container.processingController.state.first { !it.busy } }
    }

    private suspend fun cleanup(id: String) {
        container.processingController.cancel(id)
        withTimeout(10_000) { container.processingController.state.first { !it.busy } }
        container.processingController.delete(id)
        withTimeout(10_000) { container.sessionRepository.observeSession(id).first { it == null } }
    }
}
