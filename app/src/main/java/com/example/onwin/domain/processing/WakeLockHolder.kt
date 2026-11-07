package com.example.onwin.domain.processing

/**
 * Не даёт процессору уснуть, пока идёт задание. Foreground-сервис держит процесс живым,
 * но при выключенном экране CPU без wake lock засыпает.
 */
interface WakeLockHolder {

    /** Берёт wake lock; повторный вызов продлевает таймаут. */
    fun acquire()

    /** Отпускает wake lock; без [acquire] ничего не делает. */
    fun release()
}
