package com.example.onwin.domain.processing

/** Запуск и остановка foreground-сервиса обработки. */
interface ForegroundStarter {
    /**
     * Запускает сервис (или будит уже запущенный). Вызывать синхронно из действия пользователя.
     *
     * @param sessionId сессия, ради которой нужен сервис (для логов).
     * @throws ForegroundStartException если система запретила запуск.
     */
    fun start(sessionId: String)

    /** Синхронно запрашивает foreground-режим микрофона из действия пользователя. */
    fun startMicrophone(sessionId: String) = start(sessionId)

    /** Захват начинается только после успешного перехода в foreground. */
    suspend fun awaitMicrophone() {}

    /** Переключает уже запущенный сервис перед обработкой очереди. */
    suspend fun awaitProcessing() {}

    /** Сообщает, что сервис больше не нужен: очередь пуста. */
    fun stop()
}

/** Система не разрешила запустить foreground-сервис (например, приложение уже в фоне). */
class ForegroundStartException(
    cause: Throwable
) : Exception("Foreground service start not allowed: $cause", cause)
