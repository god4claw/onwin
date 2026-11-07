package com.example.onwin

import android.content.Context
import android.util.Log
import com.example.onwin.data.audio.AndroidMicrophone
import com.example.onwin.data.audio.AudioConverter
import com.example.onwin.data.audio.ConverterAudioToWav
import com.example.onwin.data.audio.GrowingWavPcmSource
import com.example.onwin.data.audio.WavMicrophoneRecorder
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.processing.PowerWakeLockHolder
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.domain.processing.DefaultProcessingController
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.repository.SessionRepository
import com.example.onwin.ui.notification.ServiceForegroundStarter
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Transcriber
import com.example.speech.impl.SherpaVadTranscriber
import java.io.File
import java.time.Clock

/**
 * Ручной DI: все синглтоны приложения. Создаётся один раз в [OnWinApp].
 *
 * @param context любой контекст; используется контекст приложения.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val recordingsDir = File(appContext.filesDir, "recordings")
    private val clock = Clock.systemUTC()

    private val transcriber: Transcriber = SherpaVadTranscriber(appContext)

    private val database = AppDatabase.create(appContext)

    /** Сессии, сегменты и WAV-файлы. */
    val sessionRepository: SessionRepository = RoomSessionRepository(database.sessionDao(), recordingsDir, clock)

    /** Запуск foreground-сервиса; сервис следит за его флагом, чтобы остановиться. */
    val foregroundStarter = ServiceForegroundStarter(appContext)

    /** Очередь обработки. Создаётся сразу: при создании он восстанавливает сессии после смерти процесса. */
    val processingController: ProcessingController = DefaultProcessingController(
        audio = ConverterAudioToWav(appContext, AudioConverter(appContext, recordingsDir)),
        repository = sessionRepository,
        transcriber = transcriber,
        speechConfig = SpeechConfig(),
        foreground = foregroundStarter,
        wakeLock = PowerWakeLockHolder(appContext),
        clock = clock,
        log = { Log.i("Processing", it) },
        microphone = WavMicrophoneRecorder(AndroidMicrophone(appContext)),
        livePcm = GrowingWavPcmSource,
        recordingTitle = { appContext.getString(R.string.recording_name) },
    )
}
