package com.example.onwin.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llm.OnDeviceLlm
import com.example.speech.api.Segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledLlmTest {
    @Test(timeout = 300_000)
    fun bundledModelCorrectsRussianTranscript() = runBlocking {
        val corrector = bundledLlm().corrector

        try {
            corrector.load()
            val corrected = corrector.correct("он пришол домой после работы")
            assertEquals("он пришел домой после работы", corrected.replace('ё', 'е'))
        } finally {
            corrector.release()
        }
    }

    @Test(timeout = 300_000)
    fun bundledModelGeneratesProtocol() = runBlocking {
        val analyzer = bundledLlm().protocolAnalyzer
        try {
            analyzer.load()
            val protocol = analyzer.analyzeProtocol(
                segments = listOf(
                    Segment(0, 0, 5_000, "Коллеги, меня зовут Анна, начнём встречу."),
                    Segment(1, 5_200, 11_000, "Я Игорь. Решили перенести релиз на 20 ноября."),
                    Segment(0, 11_200, 16_000, "Хорошо, я подготовлю сборку к 15 ноября."),
                ),
                speakerNames = mapOf(0 to "Анна", 1 to "Игорь"),
            )
            checkNotNull(protocol) { "Модель не сгенерировала протокол" }
            assertTrue(protocol.summary.isNotBlank())
            assertTrue(protocol.decisions.isNotEmpty() || protocol.actions.isNotEmpty())
        } finally {
            analyzer.release()
        }
    }

    private fun bundledLlm(): OnDeviceLlm {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.assets.openFd("llm/llm-model-v1.zip").use { asset ->
            assertTrue("Архив модели пуст", asset.length > 0)
        }
        return checkNotNull(OnDeviceLlm.create(context))
    }
}
