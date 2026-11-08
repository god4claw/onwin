package com.example.llm

import ai.onnxruntime.genai.GenAI
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import com.example.speech.api.TextGenerator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [TextGenerator] поверх ONNX Runtime GenAI: одна локальная instruct-модель
 * (Qwen2.5-0.5B-Instruct, int4) в каталоге [modelDir].
 *
 * Устройство:
 * - модель открывается один раз в [load] и живёт до [release]: загрузка int4
 *   на 0,5 млрд параметров занимает секунды, повторять её на каждую реплику нельзя;
 * - промпт оборачивается в chat-шаблон токенизатора, если он есть: instruct-модели
 *   без него отвечают хуже, а `LlmCorrector` шлёт обычную инструкцию;
 * - генерация жадная (`do_sample = false`): задача — не сочинять, а править,
 *   поэтому случайность только вредит;
 * - цикл по токенам проверяет отмену корутины на каждом шаге: `withTimeoutOrNull`
 *   в [com.example.speech.impl.LlmCorrector] не остановит нативный цикл сам.
 *
 * Экземпляр не потокобезопасен по генерации: [load], [generate] и [release]
 * вызываются последовательно, как и весь пайплайн распознавания.
 *
 * @param prepareModelDirectory возвращает каталог с конфигом, весами и токенизатором.
 * @param systemPrompt системная роль; по умолчанию — «правь, а не переписывай».
 */
class OnnxGenAiGenerator internal constructor(
    private val prepareModelDirectory: suspend () -> File,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) : TextGenerator {

    constructor(modelDir: File, systemPrompt: String = DEFAULT_SYSTEM_PROMPT) : this({ modelDir }, systemPrompt)

    private val lock = Any()
    private var model: Model? = null
    private var tokenizer: Tokenizer? = null

    override suspend fun load(): Unit = withContext(Dispatchers.IO) {
        if (synchronized(lock) { model != null }) return@withContext
        val modelDir = prepareModelDirectory()
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (this@OnnxGenAiGenerator.model != null) return@synchronized
            check(LlmModelDirectory.isComplete(modelDir)) {
                "Нет комплекта ONNX GenAI в $modelDir: нужен $CONFIG_FILE и *.onnx"
            }
            // AAR телеметрию не запускает (провайдер вырезан), но флаг ставим явно:
            // приложение офлайн, и отправлять нечего.
            GenAI.setTelemetry(false)
            val created = Model(modelDir.absolutePath)
            try {
                tokenizer = Tokenizer(created)
                model = created
            } catch (e: Throwable) {
                created.close()
                throw e
            }
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String = withContext(Dispatchers.Default) {
        require(maxTokens > 0) { "maxTokens должен быть > 0, получено $maxTokens" }
        val (model, tokenizer) = synchronized(lock) {
            checkNotNull(this@OnnxGenAiGenerator.model) { "load() не вызывался" } to
                checkNotNull(this@OnnxGenAiGenerator.tokenizer) { "load() не вызывался" }
        }
        val chatPrompt = chatPrompt(tokenizer, prompt)
        tokenizer.encode(chatPrompt).use { sequences ->
            val promptTokens = sequences.getSequence(0).size
            GeneratorParams(model).use { params ->
                params.setSearchOption("max_length", (promptTokens + maxTokens).toDouble())
                params.setSearchOption("do_sample", false)
                Generator(model, params).use { generator ->
                    generator.appendTokenSequences(sequences)
                    tokenizer.createStream().use { stream ->
                        val answer = StringBuilder()
                        while (!generator.isDone()) {
                            // Отмена должна доходить до нативного цикла: иначе
                            // таймаут LlmCorrector продолжит жечь CPU в фоне.
                            currentCoroutineContext().ensureActive()
                            generator.generateNextToken()
                            answer.append(stream.decode(generator.getLastTokenInSequence(0)))
                        }
                        answer.toString().trim()
                    }
                }
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            tokenizer?.close()
            tokenizer = null
            model?.close()
            model = null
        }
    }

    /**
     * Оборачивает инструкцию в chat-шаблон модели. Если шаблона нет (не instruct
     * модель), отправляем промпт как есть — лучше, чем ничего.
     *
     * Внимание: `applyChatTemplate` в 0.19 ждёт JSON сообщений, а не системный
     * текст; невалидный аргумент роняет процесс в нативном коде.
     */
    private fun chatPrompt(tokenizer: Tokenizer, prompt: String): String =
        try {
            tokenizer.applyChatTemplate("", ChatMessages.json(systemPrompt, prompt), null, true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            prompt
        }

    companion object {
        const val CONFIG_FILE = LlmModelDirectory.CONFIG_FILE

        const val DEFAULT_SYSTEM_PROMPT: String =
            "Ты — аккуратный редактор расшифровок русской речи. " +
                "Ты исправляешь только явные ошибки распознавания и ничего не придумываешь. " +
                "Ответ всегда ровно один: исправленная фраза, без пояснений."
    }
}
