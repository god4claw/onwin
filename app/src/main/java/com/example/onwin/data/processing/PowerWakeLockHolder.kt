package com.example.onwin.data.processing

import android.content.Context
import android.os.PowerManager
import com.example.onwin.domain.processing.WakeLockHolder

/**
 * [WakeLockHolder] на `PARTIAL_WAKE_LOCK` с предохранительным таймаутом [TIMEOUT_MS]
 * на случай, если [release] не будет вызван.
 */
class PowerWakeLockHolder(context: Context) : WakeLockHolder {

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnWin:processing")
        .apply { setReferenceCounted(false) }

    override fun acquire() {
        wakeLock.acquire(TIMEOUT_MS)
    }

    override fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private companion object {
        /** Как лимит foreground-сервиса в Android 15: дольше одно задание всё равно не проживёт. */
        const val TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
