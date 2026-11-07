package com.example.onwin.baselineprofile

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.regex.Pattern
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReleaseProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testDevice = UiDevice.getInstance(instrumentation)

    @Before
    fun requireGenerationDevice() {
        check(Build.VERSION.SDK_INT >= 33) {
            "Baseline Profile generation requires Android 13 or later."
        }
    }

    @Test
    fun firstLaunch() = rule.collect(packageName = PROFILE_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait(profileLaunchIntent())
        check(device.wait(Until.hasObject(By.text(WELCOME_BUTTON)), UI_TIMEOUT_MS)) {
            "Use a fresh install of the dedicated .profile variant to capture the welcome screen."
        }
    }

    @Test
    fun returningLaunch() {
        testDevice.openRecordings()
        rule.collect(packageName = PROFILE_PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait(profileLaunchIntent())
            device.awaitText(IMPORT_BUTTON)
        }
    }

    @Test
    fun transcript() {
        val title = "onwin-profile-${UUID.randomUUID()}"
        val fileName = "$title.wav"
        val resolver = instrumentation.context.contentResolver
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = checkNotNull(resolver.insert(downloads, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))

        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                val secondOfPcm = ByteArray(32_000)
                output.write(wavHeader(secondOfPcm.size * FIXTURE_SECONDS))
                repeat(FIXTURE_SECONDS) { output.write(secondOfPcm) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)

            testDevice.openRecordings()
            testDevice.clickWhenAvailable(By.text(IMPORT_BUTTON))
            val downloadsLabel = By.text(Pattern.compile("Downloads|Загрузки"))
            if (!testDevice.wait(Until.hasObject(By.text(fileName)), 2_000L)) {
                val drawer = By.desc(Pattern.compile(
                    "Show roots|Показать корни|Open navigation drawer|Открыть меню навигации",
                ))
                testDevice.clickWhenAvailable(drawer)
                testDevice.clickWhenAvailable(downloadsLabel)
            }
            testDevice.clickWhenAvailable(By.text(fileName))
            testDevice.awaitText("Сохранить JSON", timeoutMs = 60_000L)

            rule.collect(packageName = PROFILE_PACKAGE) {
                pressHome()
                startActivityAndWait(profileLaunchIntent())
                device.clickWhenAvailable(By.text(title))
                device.actWhenAvailable(By.res("transcript-list")) { swipe(Direction.DOWN, 0.7f) }
                device.actWhenAvailable(By.res("transcript-list")) { swipe(Direction.UP, 0.7f) }
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun wavHeader(dataSize: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + dataSize)
        .put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(dataSize).array()

    private companion object {
        const val FIXTURE_SECONDS = 65
    }
}
