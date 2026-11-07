package com.example.onwin.ui.screens.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.IOException
import android.os.SystemClock
import androidx.compose.runtime.withFrameMillis

@Composable
internal fun rememberRecordingPlayback(file: File?): RecordingPlayback {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playback = remember(context, file) { RecordingPlayback(context) }
    DisposableEffect(playback, lifecycle) {
        file?.let(playback::prepare)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) playback.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            playback.release()
        }
    }
    LaunchedEffect(playback, playback.state.playing) {
        while (playback.state.playing) {
            withFrameMillis {
                playback.refreshPosition()
            }
        }
    }
    return playback
}

internal data class RecordingPlaybackState(
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val focusUnavailable: Boolean = false,
) {
    val ready: Boolean get() = durationMs > 0
}

/** A player for one WAV. All calls and MediaPlayer callbacks run on the main thread. */
internal class RecordingPlayback(context: Context) {
    var state by mutableStateOf(RecordingPlaybackState())
        private set
    // Позиция обновляется каждый кадр и не должна тянуть за собой рекомпозицию всего дерева,
    // поэтому вынесена из структурного state в отдельный MutableLongState.
    private val position = mutableLongStateOf(0L)
    val positionState: State<Long> get() = position
    val positionMs: Long get() = position.longValue

    private val audioManager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(
            { if (it < 0) pause() },
            Handler(Looper.getMainLooper()),
        )
        .build()

    private var player: MediaPlayer? = null
    private var hasFocus = false
    private var seekingTo: Long? = null
    private var playAfterSeek = false
    private var lastRawPositionMs = 0L
    private var lastRawPositionTimeMs = 0L

    private fun resetPositionAnchor(positionMs: Long) {
        lastRawPositionMs = positionMs
        lastRawPositionTimeMs = SystemClock.elapsedRealtime()
    }

    fun prepare(file: File) {
        release()
        val media = MediaPlayer()
        player = media
        state = RecordingPlaybackState(preparing = true)
        media.setOnPreparedListener {
            if (player === media) {
                val durationMs = media.duration.toLong()
                if (durationMs > 0) state = RecordingPlaybackState(durationMs = durationMs)
                else release()
            }
        }
        media.setOnSeekCompleteListener {
            if (player === media) completeSeek(media)
        }
        media.setOnCompletionListener {
            if (player === media) {
                playAfterSeek = false
                abandonFocus()
                state = state.copy(playing = false)
                if (seekingTo == null) position.longValue = state.durationMs
            }
        }
        media.setOnErrorListener { _, _, _ ->
            if (player === media) release()
            true
        }

        try {
            media.setAudioAttributes(attributes)
            file.inputStream().use { media.setDataSource(it.fd) }
            media.prepareAsync()
        } catch (_: IOException) {
            release()
        } catch (_: IllegalArgumentException) {
            release()
        } catch (_: IllegalStateException) {
            release()
        }
    }

    fun toggle() {
        val media = player ?: return
        if (!state.ready) return
        if (state.playing || playAfterSeek) {
            pause()
        } else if (position.longValue == state.durationMs) {
            playAfterSeek = true
            seekTo(0)
        } else if (seekingTo != null) {
            playAfterSeek = true
        } else {
            play(media)
        }
    }

    fun seekTo(positionMs: Long) {
        val media = player ?: return
        if (!state.ready) return
        val targetMs = positionMs.coerceIn(0, state.durationMs)
        position.longValue = targetMs
        resetPositionAnchor(targetMs)
        if (seekingTo == null) requestSeek(media)
    }

    private fun requestSeek(media: MediaPlayer) = control {
        seekingTo = position.longValue
        media.seekTo(position.longValue, MediaPlayer.SEEK_CLOSEST)
    }

    private fun completeSeek(media: MediaPlayer) {
        val completedPosition = seekingTo ?: return
        seekingTo = null
        if (position.longValue != completedPosition) {
            requestSeek(media)
        } else {
            resetPositionAnchor(position.longValue)
            if (playAfterSeek) {
                playAfterSeek = false
                play(media)
            }
        }
    }

    private fun play(media: MediaPlayer) = control {
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) {
            resetPositionAnchor(position.longValue)
            media.start()
            state = state.copy(playing = true, focusUnavailable = false)
        } else {
            state = state.copy(focusUnavailable = true)
        }
    }

    fun pause() {
        playAfterSeek = false
        val media = player
        if (state.playing && media != null) control {
            media.pause()
            state = state.copy(playing = false)
        }
        abandonFocus()
    }

    fun refreshPosition() {
        val media = player ?: return
        if (!state.playing || seekingTo != null) return
        control {
            val now = SystemClock.elapsedRealtime()
            val rawPosition = media.currentPosition.toLong().coerceIn(0, state.durationMs)
            if (rawPosition != lastRawPositionMs) {
                lastRawPositionMs = rawPosition
                lastRawPositionTimeMs = now
            }
            // Плавный прирост между обновлениями буфера MediaPlayer (ограничен 150 мс на случай задержки аудио)
            val interpolated = (lastRawPositionMs + (now - lastRawPositionTimeMs).coerceIn(0L, 150L))
                .coerceIn(0L, state.durationMs)
            // maxOf гарантирует, что ползунок никогда не дернется назад из-за погрешности буфера
            position.longValue = maxOf(position.longValue, interpolated)
        }
    }

    fun release() {
        val media = player
        player = null
        media?.release()
        abandonFocus()
        seekingTo = null
        playAfterSeek = false
        resetPositionAnchor(0L)
        position.longValue = 0L
        state = RecordingPlaybackState()
    }

    private fun abandonFocus() {
        if (hasFocus) {
            hasFocus = false
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private inline fun control(action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalStateException) {
            release()
        }
    }
}
