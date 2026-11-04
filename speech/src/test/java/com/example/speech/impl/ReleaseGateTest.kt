package com.example.speech.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGateTest {

    @Test
    fun `does nothing without request`() {
        var released = 0
        val gate = ReleaseGate { released++ }

        assertFalse(gate.releaseIfRequested())
        assertEquals(0, released)
    }

    @Test
    fun `releases exactly once per request`() {
        var released = 0
        val gate = ReleaseGate { released++ }

        gate.request()
        assertTrue(gate.isRequested)
        assertTrue(gate.releaseIfRequested())
        assertFalse(gate.isRequested)
        assertEquals(1, released)

        assertFalse(gate.releaseIfRequested())
        assertEquals(1, released)
    }

    @Test
    fun `cancel discards pending request`() {
        var released = 0
        val gate = ReleaseGate { released++ }

        gate.request()
        gate.cancel()

        assertFalse(gate.releaseIfRequested())
        assertEquals(0, released)
    }

    @Test
    fun `new request after release works again`() {
        var released = 0
        val gate = ReleaseGate { released++ }

        gate.request()
        gate.releaseIfRequested()
        gate.request()
        gate.releaseIfRequested()

        assertEquals(2, released)
    }
}
