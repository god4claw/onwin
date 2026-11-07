package com.example.speech.impl

import com.example.speech.api.Segment
import com.example.speech.api.TextCorrector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPostprocessorTest {

    private class MarkerCorrector(private val marker: String) : TextCorrector {
        var loadCalls = 0
        var releaseCalls = 0

        override suspend fun load() {
            loadCalls++
        }

        override fun release() {
            releaseCalls++
        }

        override suspend fun correct(text: String): String = "$text$marker"
    }

    private val segments = listOf(
        Segment(speaker = 0, startMs = 0, endMs = 1_200, text = "первая"),
        Segment(speaker = 1, startMs = 1_500, endMs = 3_000, text = "вторая"),
    )

    @Test
    fun `text is corrected while speakers and timings are preserved`() = runBlocking {
        val result = TranscriptPostprocessor(MarkerCorrector("!")).process(segments)

        assertEquals(listOf("первая!", "вторая!"), result.map { it.text })
        assertEquals(listOf(0, 1), result.map { it.speaker })
        assertEquals(listOf(0L, 1_500L), result.map { it.startMs })
        assertEquals(listOf(1_200L, 3_000L), result.map { it.endMs })
    }

    @Test
    fun `unchanged segments are not copied`() = runBlocking {
        val identity = object : TextCorrector {
            override suspend fun load() = Unit
            override fun release() = Unit
            override suspend fun correct(text: String): String = text
        }

        val result = TranscriptPostprocessor(identity).process(segments)

        assertEquals(segments, result)
    }

    @Test
    fun `composite corrector applies layers in order`() = runBlocking {
        val composite = CompositeCorrector(listOf(MarkerCorrector("1"), MarkerCorrector("2")))

        assertEquals("текст12", composite.correct("текст"))
    }

    @Test
    fun `composite loads and releases every layer`() = runBlocking {
        val first = MarkerCorrector("1")
        val second = MarkerCorrector("2")
        val composite = CompositeCorrector(listOf(first, second))

        composite.load()
        composite.release()

        assertEquals(1, first.loadCalls)
        assertEquals(1, second.loadCalls)
        assertEquals(1, first.releaseCalls)
        assertEquals(1, second.releaseCalls)
    }
}
