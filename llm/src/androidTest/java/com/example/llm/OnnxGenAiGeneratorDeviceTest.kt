package com.example.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.speech.impl.LlmCorrector
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Р РµР°Р»СЊРЅР°СЏ РїСЂРѕРІРµСЂРєР° ONNX Runtime GenAI РЅР° СѓСЃС‚СЂРѕР№СЃС‚РІРµ.
 *
 * РњРѕРґРµР»СЊ РЅРµ РІС…РѕРґРёС‚ РІ APK Рё РЅРµ РїР°РєСѓРµС‚СЃСЏ РІ С‚РµСЃС‚: С‚РµСЃС‚ РїСЂРѕРїСѓСЃРєР°РµС‚СЃСЏ, РµСЃР»Рё РµС‘ РЅРµС‚.
 * Р—Р°Р»РёС‚СЊ: `llm/scripts/push-model.ps1 -Package com.example.llm` Р»РёР±Рѕ С‡РµСЂРµР·
 * `-Pandroid.testInstrumentationRunnerArguments.modelDir=/sdcard/Download/onwin-llm`.
 */
@RunWith(AndroidJUnit4::class)
class OnnxGenAiGeneratorDeviceTest {

    @Test
    fun generatesCyrillicText_whenModelIsPushed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val explicit = InstrumentationRegistry.getArguments().getString("modelDir")?.let(::File)
        val dir = explicit
            ?.takeIf(LlmModelDirectory::isComplete)
            ?: OnDeviceLlm.modelDirectory(instrumentation.targetContext)
        assumeTrue("РњРѕРґРµР»СЊ РЅРµ Р·Р°Р»РёС‚Р°: llm/scripts/push-model.ps1", dir != null)

        val generator = OnnxGenAiGenerator(checkNotNull(dir))
        try {
            generator.load()
            val answer = generator.generate("РСЃРїСЂР°РІСЊ РѕС€РёР±РєСѓ СЂР°СЃРїРѕР·РЅР°РІР°РЅРёСЏ: СЏ РїСЂРёРґС‘С‚ Р·Р°РІС‚СЂР°.", 64)
            assertTrue("РџСѓСЃС‚РѕР№ РѕС‚РІРµС‚ РјРѕРґРµР»Рё", answer.isNotBlank())
            assertTrue(
                "РћС‚РІРµС‚ Р±РµР· РєРёСЂРёР»Р»РёС†С‹ вЂ” РјРѕРґРµР»СЊ РЅРµ С‚Р°: $answer",
                answer.any { it in 'а'..'я' || it in 'А'..'Я' },
            )
        } finally {
            generator.release()
        }
    }

    /**
     * РџРѕР»РЅС‹Р№ СЃС‚РµРє РєРѕСЂСЂРµРєС†РёРё: РїСЂРѕРјРїС‚, РјР°СЃРєРё С‡РёСЃРµР», РіРµРЅРµСЂР°С†РёСЏ Рё РіР°СЂРґ.
     * Р’РѕР·РІСЂР°С‰Р°РµС‚ С‚РµРєСЃС‚, РєРѕС‚РѕСЂС‹Р№ СѓРІРёРґРёС‚ РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ (Р»РѕРі вЂ” `LlmCorrectorTest`).
     */
    @Test
    fun llmCorrector_returnsTextThatPassesTheGuard() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = OnDeviceLlm.modelDirectory(context)
        assumeTrue("РњРѕРґРµР»СЊ РЅРµ Р·Р°Р»РёС‚Р°: llm/scripts/push-model.ps1", dir != null)

        val corrector = LlmCorrector(
            OnnxGenAiGenerator(checkNotNull(dir)),
            maxOutputTokens = OnDeviceLlm.CORRECTION_MAX_TOKENS,
            budgetMs = 20_000,
        )
        try {
            corrector.load()
            val source = "СЏ РїСЂРёРґС‘С‚ Р·Р°РІС‚СЂР° РІ РґРµРІСЏС‚СЊ"
            val corrected = corrector.correct(source)
            android.util.Log.i("LlmCorrectorTest", "source=$source corrected=$corrected")
            assertFalse("РџСѓСЃС‚РѕР№ С‚РµРєСЃС‚ РїРѕСЃР»Рµ РєРѕСЂСЂРµРєС†РёРё", corrected.isBlank())
        } finally {
            corrector.release()
        }
    }
}

