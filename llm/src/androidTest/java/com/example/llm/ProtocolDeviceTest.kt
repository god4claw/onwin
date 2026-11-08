package com.example.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.speech.api.Segment
import com.example.speech.api.TranscriptLanguage
import com.example.speech.impl.LlmDialogueAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Реальный ONNX GenAI-проход на эмуляторе: русский и английский протокол.
 *
 * Модель лежит вне APK:
 * `adb push llm/models/qwen2.5-0.5b-instruct-int4/. /sdcard/Android/data/com.example.llm.test/files/llm/`
 * и запуск:
 * `adb shell am instrument -w -e class com.example.llm.ProtocolDeviceTest com.example.llm.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class ProtocolDeviceTest {

    @Test(timeout = 300_000)
    fun russianAndEnglishProtocols(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelDir = File(
            args.getString("modelDir")
                ?: "/sdcard/Android/data/com.example.llm.test/files/llm",
        )
        assumeTrue("Нет модели в $modelDir", File(modelDir, "genai_config.json").isFile)

        val generator = OnnxGenAiGenerator(modelDir)
        val analyzer = LlmDialogueAnalyzer(generator)
        try {
            analyzer.load()

            val russian = analyzer.analyzeProtocol(
                segments = listOf(
                    Segment(0, 0, 5_000, "Коллеги, меня зовут Анна, начнём встречу."),
                    Segment(1, 5_200, 11_000, "Я Игорь. Решили перенести релиз на 20 ноября."),
                    Segment(0, 11_200, 16_000, "Хорошо, я подготовлю сборку к 15 ноября."),
                ),
                speakerNames = mapOf(0 to "Анна", 1 to "Игорь"),
            )
            assertTrue("RU: нет протокола", russian != null)
            assertEquals(TranscriptLanguage.RU, russian!!.language)
            assertEquals(listOf("Анна", "Игорь"), russian.participants.map { it.name })
            assertTrue("RU: пустой summary", russian.summary.isNotBlank())
            assertTrue(
                "RU: нет решений/поручений: $russian",
                russian.decisions.isNotEmpty() || russian.actions.isNotEmpty(),
            )

            val english = analyzer.analyzeProtocol(
                segments = listOf(
                    Segment(0, 0, 4_000, "We discussed the release schedule."),
                    Segment(1, 4_200, 9_000, "We decided to move the release to November 20."),
                    Segment(0, 9_200, 13_000, "I will prepare the build by November 15."),
                ),
            )
            assertTrue("EN: нет протокола", english != null)
            assertEquals(TranscriptLanguage.EN, english!!.language)
            assertTrue("EN: пустой summary", english.summary.isNotBlank())
            assertTrue("EN: summary не на английском: ${english.summary}", english.summary.any { it.isLatin() })
        } finally {
            analyzer.release()
        }
    }

    private fun Char.isLatin(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
