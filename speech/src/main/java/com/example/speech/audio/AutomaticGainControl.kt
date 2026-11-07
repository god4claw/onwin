package com.example.speech.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Автоматическая регулировка усиления перед VAD.
 *
 * Зачем: Silero VAD не слышит собеседника, который сидит дальше от микрофона.
 * На реальной записи разговора дальний голос шёл на −33…−37 dBFS при фоне −47,
 * и VAD пропустил ~9 с разборчивой речи даже с порогом 0,25. После выравнивания
 * громкости та же речь находится и распознаётся.
 *
 * Как устроено (только прошлый сигнал, без задержки и без буфера: длина выхода
 * всегда равна длине входа, таймкоды VAD не сдвигаются):
 * - уровень меряется по кадрам [FRAME_MS] мс;
 * - уровень фона — минимум сглаженного (по [FLOOR_SMOOTH_FRAMES] кадрам)
 *   уровня за последние [NOISE_WINDOW_MS] мс; кадры цифровой тишины (ниже
 *   [ABSOLUTE_GATE_DBFS]) в оценку не входят. Раньше трекер «быстро вниз,
 *   медленно вверх» залипал на −97 dBFS после нулей в начале записи, и все
 *   паузы считались речью;
 * - кадр считается речью, если он на [GATE_DB] громче фона и громче
 *   [ABSOLUTE_GATE_DBFS]; только на таких кадрах обновляется оценка уровня
 *   речи и может расти усиление — стационарный фон не раздувается;
 * - в паузах усиление плавно возвращается к 0 дБ: фон остаётся на своём уровне,
 *   и VAD видит тишину. Иначе после тихой фразы усиленное дыхание и шум в
 *   паузе склеивали реплики (на живом монологе — один кусок на 25 с);
 * - усиление = targetDbfs − уровень речи, в пределах 0..maxGainDb: громкую речь
 *   не приглушаем, только поднимаем тихую;
 * - лимитер: усиление кадра не выше запаса до [LIMIT_DBFS] по пику прошлого
 *   кадра, плюс мягкое ограничение выборок выше [SOFT_CLIP_KNEE] на атаках.
 *
 * Не потокобезопасен: один экземпляр на одну streaming-сессию.
 */
internal class AutomaticGainControl(
    private val targetDbfs: Float = -20f,
    private val maxGainDb: Float = 24f,
    sampleRate: Int = VadSegmenter.SAMPLE_RATE,
) {
    init {
        require(maxGainDb >= 0f) { "maxGainDb должен быть >= 0, получено $maxGainDb" }
        require(sampleRate > 0) { "sampleRate должен быть > 0, получено $sampleRate" }
    }

    private val frameSize = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)

    private var frameFill = 0
    private var frameSumSquares = 0.0
    private var framePeak = 0f

    // Минимум-статистика фона: последние уровни (для сглаживания) и окно сглаженных.
    private val recentLevels = FloatArray(FLOOR_SMOOTH_FRAMES)
    private var recentCount = 0
    private val floorWindow = FloatArray((NOISE_WINDOW_MS / FRAME_MS).coerceAtLeast(1))
    private var floorCount = 0
    private var floorNext = 0
    private var noiseFloorDb = Float.NaN
    private var speechLevelDb = Float.NaN
    private var gainDb = 0f

    // Линейное усиление, которое применяется прямо сейчас, и шаг, которым оно
    // плавно идёт к цели кадра (без «ступенек» на границе кадров).
    private var appliedGain = 1f
    private var targetGain = 1f
    private var gainStep = 0f

    /** Текущее усиление в дБ (для лога и тестов). */
    val currentGainDb: Float get() = 20f * log10(appliedGain)

    /** Наибольшее усиление за сессию, дБ. */
    var maxAppliedGainDb: Float = 0f
        private set

    fun process(samples: FloatArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            val x = samples[i]
            frameSumSquares += x.toDouble() * x
            val magnitude = abs(x)
            if (magnitude > framePeak) framePeak = magnitude

            if (gainStep != 0f) {
                appliedGain += gainStep
                val reached = if (gainStep > 0f) appliedGain >= targetGain else appliedGain <= targetGain
                if (reached) {
                    appliedGain = targetGain
                    gainStep = 0f
                }
            }
            out[i] = softClip(x * appliedGain)

            if (++frameFill == frameSize) endFrame()
        }
        return out
    }

    fun reset() {
        frameFill = 0
        frameSumSquares = 0.0
        framePeak = 0f
        recentCount = 0
        floorCount = 0
        floorNext = 0
        noiseFloorDb = Float.NaN
        speechLevelDb = Float.NaN
        gainDb = 0f
        appliedGain = 1f
        targetGain = 1f
        gainStep = 0f
        maxAppliedGainDb = 0f
    }

    private fun endFrame() {
        val rms = sqrt(frameSumSquares / frameSize).toFloat()
        val levelDb = toDb(rms)
        updateNoiseFloor(levelDb)

        val floor = noiseFloorDb
        if (!floor.isNaN() && levelDb > floor + GATE_DB && levelDb > ABSOLUTE_GATE_DBFS) {
            val previous = speechLevelDb
            speechLevelDb = if (previous.isNaN()) {
                levelDb
            } else {
                val coef = if (levelDb > previous) LEVEL_ATTACK else LEVEL_RELEASE
                previous + (levelDb - previous) * coef
            }
            val desired = (targetDbfs - speechLevelDb).coerceIn(0f, maxGainDb)
            val coef = if (desired < gainDb) GAIN_ATTACK else GAIN_RELEASE
            gainDb += (desired - gainDb) * coef
        } else {
            gainDb -= gainDb * PAUSE_DECAY
        }

        // Лимитер по пику только что прошедшего кадра: следующий кадр с тем же
        // пиком не должен уйти выше LIMIT_DBFS.
        val headroomDb = if (framePeak > 0f) LIMIT_DBFS - toDb(framePeak) else maxGainDb
        val frameGainDb = min(gainDb, headroomDb).coerceAtLeast(0f)
        targetGain = 10f.pow(frameGainDb / 20f)
        gainStep = (targetGain - appliedGain) / frameSize
        maxAppliedGainDb = max(maxAppliedGainDb, frameGainDb)

        frameFill = 0
        frameSumSquares = 0.0
        framePeak = 0f
    }

    private fun updateNoiseFloor(levelDb: Float) {
        if (levelDb <= ABSOLUTE_GATE_DBFS) return // цифровая тишина — не «фон»

        recentLevels[recentCount % FLOOR_SMOOTH_FRAMES] = levelDb
        recentCount++
        val used = min(recentCount, FLOOR_SMOOTH_FRAMES)
        var sum = 0f
        for (i in 0 until used) sum += recentLevels[i]

        floorWindow[floorNext] = sum / used
        floorNext = (floorNext + 1) % floorWindow.size
        if (floorCount < floorWindow.size) floorCount++

        var minimum = Float.MAX_VALUE
        for (i in 0 until floorCount) minimum = min(minimum, floorWindow[i])
        noiseFloorDb = minimum
    }

    private fun toDb(value: Float): Float = 20f * log10(max(value, MIN_LEVEL))

    private fun softClip(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= SOFT_CLIP_KNEE) return value
        val headroom = 1f - SOFT_CLIP_KNEE
        val limited = SOFT_CLIP_KNEE + headroom * tanh((magnitude - SOFT_CLIP_KNEE) / headroom)
        return if (value < 0f) -limited else limited
    }

    internal companion object {
        const val FRAME_MS = 20

        /** Кадр — речь, если он на столько дБ громче фона. */
        const val GATE_DB = 6f

        /** Ниже этого уровня — тишина, сколько бы ни было до фона (цифровой ноль). */
        const val ABSOLUTE_GATE_DBFS = -70f

        const val LIMIT_DBFS = -1f
        const val SOFT_CLIP_KNEE = 0.9f

        /** Оценка уровня речи: быстро вверх (~30 мс), медленно вниз (~400 мс). */
        const val LEVEL_ATTACK = 0.5f
        const val LEVEL_RELEASE = 0.05f

        /** Усиление: быстро вниз (~30 мс), вверх за ~100 мс, в паузе к 0 дБ за ~130 мс. */
        const val GAIN_ATTACK = 0.5f
        const val GAIN_RELEASE = 0.2f
        const val PAUSE_DECAY = 0.15f

        /**
         * Фон — минимум за 5 с: в живой речи за это время почти всегда есть пауза,
         * а один тихий кадр перестаёт влиять на оценку через 5 с.
         */
        const val NOISE_WINDOW_MS = 5_000
        const val FLOOR_SMOOTH_FRAMES = 3

        private const val MIN_LEVEL = 1e-5f // −100 dBFS
    }
}
