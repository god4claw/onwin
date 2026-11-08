package com.example.onwin.ui

import com.example.onwin.domain.processing.ProcessingStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {

    @Test
    fun formatClock_usesMinutesAndAddsHoursOnlyWhenNeeded() {
        assertEquals("00:00", formatClock(0))
        assertEquals("00:12", formatClock(12_999))
        assertEquals("59:59", formatClock(3_599_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("00:00", formatClock(-5))
    }

    @Test
    fun stagesAreNumberedOneToThree_exceptModelLoadingAndCorrection() {
        assertEquals(listOf(1, null, 2, 3, null), ProcessingStage.entries.map { it.step })
        assertEquals(STEP_COUNT, ProcessingStage.entries.mapNotNull { it.step }.max())
        assertNull(ProcessingStage.LOADING_MODELS.step)
        assertNull(ProcessingStage.CORRECTING.step)
    }
}
