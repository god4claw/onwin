package com.example.onwin.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdStartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun prepareRecordingsScreen() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).openRecordings()
    }

    @Test
    fun withoutProfile() = measure(CompilationMode.None())

    @Test
    fun withBaselineProfile() = measure(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = PROFILE_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait(profileLaunchIntent())
        device.awaitText(IMPORT_BUTTON)
    }
}
