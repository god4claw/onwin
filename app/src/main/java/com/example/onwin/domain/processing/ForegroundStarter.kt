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

    /** Request microphone foreground mode synchronously from the user action. */
    fun startMicrophone(sessionId: String) = start(sessionId)

    /** Request both foreground types when speech processing starts during capture. */
    fun startMicrophone(sessionId: String, processing: Boolean) = startMicrophone(sessionId)

    /** Capture may begin only after foreground promotion succeeds. */
    suspend fun awaitMicrophone() {}

    /** Wait for processing foreground mode without disabling any active microphone capture. */
    suspend fun awaitProcessing() {}

    /** Drop the microphone type after capture has released the microphone. */
    fun finishMicrophone() {}

    /** Сообщает, что сервис больше не нужен: очередь пуста. */
    fun stop()
}

/** Система не разрешила запустить foreground-сервис (например, приложение уже в фоне). */
class ForegroundStartException(cause: Throwable) : Exception("Foreground service start not allowed: $cause", cause)
