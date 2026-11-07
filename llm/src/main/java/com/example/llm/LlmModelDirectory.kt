package com.example.llm

import java.io.File

/**
 * Что должно лежать в каталоге модели, чтобы ONNX Runtime GenAI её открыл.
 *
 * Комплект из `genai_config.json`, `model.onnx` (или `model_q4.onnx` и т. п.),
 * `model.onnx.data` и файлов токенизатора готовит `llm/scripts/fetch-model.ps1`;
 * приложение только проверяет, что каталог не пустой и не битый по составу.
 *
 * Проверка намеренно дешёвая: содержимое файлов сверяет `verifyLlmModel`, а
 * здесь важно не пытаться открыть модель, которой нет.
 */
object LlmModelDirectory {

    /** Файл, по которому ONNX Runtime GenAI понимает, что за модель перед ним. */
    const val CONFIG_FILE = "genai_config.json"

    /** `true`, если [dir] похож на распакованный комплект ONNX GenAI. */
    fun isComplete(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory) return false
        if (!File(dir, CONFIG_FILE).isFile) return false
        return dir.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".onnx") }
    }
}
