package com.example.llm

/**
 * Сообщения для chat-шаблона ONNX Runtime GenAI.
 *
 * В 0.19 `Tokenizer.applyChatTemplate` принимает не пару «system/user», а JSON
 * массива сообщений; невалидный JSON роняет процесс в нативном коде, а не
 * бросает исключение, поэтому строка собирается здесь и проверяется JVM-тестом.
 */
internal object ChatMessages {

    /** JSON вида `[{"role":"system","content":"..."},{"role":"user","content":"..."}]`. */
    fun json(system: String, user: String): String = buildString {
        append('[')
        var first = true
        if (system.isNotBlank()) {
            append("{\"role\":\"system\",\"content\":\"")
            append(escape(system))
            append("\"}")
            first = false
        }
        if (!first) append(',')
        append("{\"role\":\"user\",\"content\":\"")
        append(escape(user))
        append("\"}]")
    }

    /** Экранирование строки для JSON; переводы строк — `\n`, управляющие — `\u00XX`. */
    fun escape(text: String): String = buildString(text.length + 16) {
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (char < ' ') {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
            }
        }
    }
}
