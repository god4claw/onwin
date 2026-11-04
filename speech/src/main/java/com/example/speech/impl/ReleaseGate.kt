package com.example.speech.impl

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Отложенное освобождение native-моделей.
 *
 * Проблема: `release()` можно вызвать, пока идёт расшифровка. Освободить
 * native-объекты в этот момент нельзя — это use-after-free в JNI. Но и
 * молча игнорировать вызов нельзя: вызывающий рассчитывает, что память
 * (сотни мегабайт весов) освободится.
 *
 * Решение: если `release()` не смог взять mutex сессии, он запоминается
 * здесь, а сессия под тем же mutex вызывает [releaseIfRequested] в `finally`.
 * Так освобождение всегда происходит между операциями и никогда — внутри
 * работающего native-вызова.
 */
internal class ReleaseGate(private val release: () -> Unit) {

    private val requested = AtomicBoolean(false)

    val isRequested: Boolean get() = requested.get()

    /** Запрос на освобождение, когда mutex занят. */
    fun request() {
        requested.set(true)
    }

    /** Отменяет отложенный запрос: новая [load] важнее старого `release()`. */
    fun cancel() {
        requested.set(false)
    }

    /**
     * Вызывать под тем же mutex, который защищает native-объекты.
     * Возвращает `true`, если освобождение действительно произошло.
     */
    fun releaseIfRequested(): Boolean {
        if (!requested.compareAndSet(true, false)) return false
        release()
        return true
    }
}
