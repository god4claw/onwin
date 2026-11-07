package com.example.onwin

import android.content.Context
import android.util.Log
import com.example.onwin.data.audio.AndroidMicrophone
import com.example.onwin.data.audio.AudioConverter
import com.example.onwin.data.audio.ConverterAudioToWav
import com.example.onwin.data.audio.GrowingWavPcmSource
import com.example.onwin.data.audio.WavMicrophoneRecorder
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.preferences.WelcomePreferences
import com.example.onwin.data.processing.PowerWakeLockHolder
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.domain.processing.AudioToWav
import com.example.onwin.domain.processing.DefaultProcessingController
import com.example.onwin.domain.processing.MicrophoneRecorder
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.WakeLockHolder
import com.example.onwin.domain.repository.SessionRepository
import com.example.onwin.domain.repository.TranscriptOnboardingRepository
import com.example.onwin.domain.repository.WelcomeRepository
import com.example.onwin.ui.notification.ServiceForegroundStarter
import com.example.llm.OnDeviceLlm
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Transcriber
import com.example.speech.impl.SherpaVadTranscriber
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppBindings {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.create(context)

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun sessionRepository(
        @ApplicationContext context: Context,
        database: AppDatabase,
        clock: Clock,
    ): SessionRepository = RoomSessionRepository(database.sessionDao(), recordingsDir(context), clock)

    @Provides
    @Singleton
    fun transcriber(@ApplicationContext context: Context): Transcriber = SherpaVadTranscriber(context)

    @Provides
    @Singleton
    fun foregroundStarter(@ApplicationContext context: Context): ServiceForegroundStarter =
        ServiceForegroundStarter(context)

    @Provides
    fun audio(@ApplicationContext context: Context): AudioToWav =
        ConverterAudioToWav(context, AudioConverter(context, recordingsDir(context)))

    @Provides
    fun microphone(@ApplicationContext context: Context): MicrophoneRecorder =
        WavMicrophoneRecorder(AndroidMicrophone(context))

    @Provides
    fun wakeLock(@ApplicationContext context: Context): WakeLockHolder = PowerWakeLockHolder(context)

    @Provides
    @Singleton
    fun processingController(
        @ApplicationContext context: Context,
        audio: AudioToWav,
        repository: SessionRepository,
        transcriber: Transcriber,
        foreground: ServiceForegroundStarter,
        wakeLock: WakeLockHolder,
        clock: Clock,
        microphone: MicrophoneRecorder,
    ): ProcessingController {
        // Модели нет — коррекции и анализа нет: приложение работает ровно как раньше.
        val llm = OnDeviceLlm.create(context)
        return DefaultProcessingController(
            audio = audio,
            repository = repository,
            transcriber = transcriber,
            speechConfig = SpeechConfig(),
            foreground = foreground,
            wakeLock = wakeLock,
            clock = clock,
            log = { Log.i("Processing", it) },
            microphone = microphone,
            livePcm = GrowingWavPcmSource,
            recordingTitle = { context.getString(R.string.recording_name) },
            onTranscriptionCompleted = (context as OnWinApp)::onTranscriptionCompleted,
            corrector = llm?.corrector,
            analyzer = llm?.analyzer,
        )
    }

    @Provides
    fun welcomeRepository(preferences: WelcomePreferences): WelcomeRepository = preferences

    @Provides
    fun transcriptOnboardingRepository(preferences: WelcomePreferences): TranscriptOnboardingRepository = preferences

    private fun recordingsDir(context: Context): File = File(context.filesDir, "recordings")
}
