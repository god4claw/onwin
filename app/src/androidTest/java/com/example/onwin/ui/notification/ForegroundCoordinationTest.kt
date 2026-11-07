package com.example.onwin.ui.notification

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production starter with service launch intercepted; no microphone hardware needed. */
@RunWith(AndroidJUnit4::class)
class ForegroundCoordinationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun captureAndProcessingWaitForTheirOwnPromotionAndImportsKeepMicrophoneMode() = runBlocking {
        var launches = 0
        val starter = ServiceForegroundStarter(object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName {
                launches++
                return ComponentName(context, ProcessingService::class.java)
            }
        })
        starter.startMicrophone("mic")
        val microphone = starter.request.value!!
        val ready = async(start = CoroutineStart.UNDISPATCHED) { starter.awaitMicrophone() }
        assertFalse(ready.isCompleted)
        starter.start("import")
        assertSame(microphone, starter.request.value)
        assertEquals(1, launches)
        microphone.promoted()
        ready.await()
        starter.finishMicrophone()
        val processingReady = async(start = CoroutineStart.UNDISPATCHED) { starter.awaitProcessing() }
        val processing = starter.request.value!!
        assertFalse(processing.microphone)
        assertFalse(processingReady.isCompleted)
        processing.promoted()
        processingReady.await()
        assertEquals(1, launches)
        starter.stop()
        assertFalse(starter.keepForeground.value)
        // A delayed service start must still be able to call startForeground before stopping.
        assertSame(processing, starter.request.value)
    }

    @Test fun microphoneUpgradeKeepsProcessingAndDowngradeDropsOnlyMicrophone() = runBlocking {
        val starter = ServiceForegroundStarter(object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName =
                ComponentName(context, ProcessingService::class.java)
        })
        starter.start("import")
        val processingReady = async(start = CoroutineStart.UNDISPATCHED) { starter.awaitProcessing() }
        starter.startMicrophone("mic")
        val both = checkNotNull(starter.request.value)
        assertTrue(both.processing && both.microphone)
        assertFalse(processingReady.isCompleted)
        both.promoted()
        processingReady.await()
        starter.awaitMicrophone()

        starter.finishMicrophone()
        val processing = checkNotNull(starter.request.value)
        assertTrue(processing.processing)
        assertFalse(processing.microphone)
        assertTrue(starter.keepForeground.value)
        starter.awaitProcessing()
    }

    @Test fun captureAndProcessingCancelIntentsAreDistinct() {
        val capture = ProcessingService.cancelIntent(context, "mic")
        val processing = ProcessingService.cancelIntent(context, "import")
        assertFalse(capture.filterEquals(processing))
    }

    @Test fun microphoneServiceIsPrivateAndDeclaresCaptureType() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, ProcessingService::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
    }
}
