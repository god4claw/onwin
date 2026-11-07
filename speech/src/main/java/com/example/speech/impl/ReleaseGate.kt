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
 * Решение: [release] под общим [handoff] сначала ставит флаг, затем пытается
 * взять mutex сессии. Сессия отпускает mutex только под тем же [handoff],
 * предварительно проверив флаг. Поэтому запрос не может потеряться между
 * проверкой и разблокировкой: либо его видит [endSession], либо [release]
 * берёт уже свободный mutex.
 *
 * @param releaseModels освобождает native-объекты; вызывается под mutex сессии.
 * @param tryAcquire пытается взять mutex сессии без ожидания.
 * @param unlock отпускает mutex сессии.
 */
internal class ReleaseGate(
    private val releaseModels: () -> Unit,
    private val tryAcquire: () -> Boolean,
    private val unlock: () -> Unit,
) {

    private val handoff = Any()
    private val requested = AtomicBoolean(false)

    val isRequested: Boolean get() = requested.get()

    /**
     * Запрашивает освобождение. Если сессия не идёт, освобождает сразу;
     * иначе запрос выполнит [endSession] в её `finally`.
     */
    fun release() {
        synchronized(handoff) {
            requested.set(true)
            if (tryAcquire()) {
                try {
                    releaseIfRequested()
                } finally {
                    unlock()
                }
            }
        }
    }

    /**
     * Завершает сессию: выполняет отложенный `release()` и отпускает mutex.
     * Вызывать только когда mutex сессии удерживается, в `finally`.
     */
    fun endSession() {
        synchronized(handoff) {
            try {
                releaseIfRequested()
            } finally {
                unlock()
            }
        }
    }

    /**
     * Завершает `load()` и отпускает mutex. При успешной загрузке отложенный
     * `release()` сбрасывается: новая загрузка важнее старого запроса. Если
     * загрузка упала, запрос сохраняется — освобождать всё равно нужно.
     */
    fun endLoad(cancelPending: Boolean) {
        synchronized(handoff) {
            try {
                if (cancelPending) requested.set(false)
            } finally {
                unlock()
            }
        }
    }

    private fun releaseIfRequested(): Boolean {
        if (!requested.compareAndSet(true, false)) return false
        releaseModels()
        return true
    }
}
