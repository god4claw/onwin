package com.example.speech.impl

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGateTest {

    @Test
    fun `does nothing without request`() {
        val lock = FakeLock(held = true)
        var released = 0
        val gate = gate(lock) { released++ }

        gate.endSession()

        assertEquals(0, released)
        assertFalse(lock.held.get())
    }

    @Test
    fun `releases immediately when session is idle`() {
        val lock = FakeLock(held = false)
        var released = 0
        val gate = gate(lock) { released++ }

        gate.release()

        assertEquals(1, released)
        assertFalse(lock.held.get())
    }

    @Test
    fun `defers release while session is active`() {
        val lock = FakeLock(held = true)
        var released = 0
        val gate = gate(lock) { released++ }

        gate.release()

        assertEquals(0, released)
        assertTrue(gate.isRequested)
        // release() не имеет права отпускать чужой mutex.
        assertTrue(lock.held.get())

        gate.endSession()

        assertEquals(1, released)
        assertFalse(gate.isRequested)
        assertFalse(lock.held.get())
    }

    @Test
    fun `release after session ended is not lost`() {
        val lock = FakeLock(held = true)
        var released = 0
        val gate = gate(lock) { released++ }

        // Сессия уже проверила флаг и завершилась, release() приходит следом.
        gate.endSession()
        gate.release()

        assertEquals(1, released)
        assertFalse(lock.held.get())
    }

    @Test
    fun `load cancels pending release`() {
        val lock = FakeLock(held = true)
        var released = 0
        val gate = gate(lock) { released++ }

        gate.release()
        assertTrue(gate.isRequested)

        gate.endLoad(cancelPending = true)

        assertEquals(0, released)
        assertFalse(gate.isRequested)
        assertFalse(lock.held.get())
    }

    @Test
    fun `failed load keeps pending release`() {
        val lock = FakeLock(held = true)
        var released = 0
        val gate = gate(lock) { released++ }

        gate.release()
        gate.endLoad(cancelPending = false)

        assertTrue(gate.isRequested)
        assertFalse(lock.held.get())

        // Следующая сессия довыполнит запрос.
        lock.held.set(true)
        gate.endSession()
        assertEquals(1, released)
        assertFalse(lock.held.get())
    }

    @Test
    fun `release after load still works`() {
        val lock = FakeLock(held = true)
        var released = 0
        val gate = gate(lock) { released++ }

        gate.release()
        gate.endLoad(cancelPending = true)
        gate.release()

        assertEquals(1, released)
        assertFalse(lock.held.get())
    }

    @Test
    fun `release racing with endSession releases exactly once`() {
        repeat(200) {
            val lock = FakeLock(held = true)
            val released = AtomicInteger()
            val gate = gate(lock) { released.incrementAndGet() }

            val releaser = thread(name = "release") { gate.release() }
            gate.endSession()
            releaser.join()

            assertEquals(1, released.get())
            assertFalse(lock.held.get())
        }
    }

    private fun gate(lock: FakeLock, release: () -> Unit): ReleaseGate =
        ReleaseGate(
            releaseModels = release,
            tryAcquire = lock::tryAcquire,
            unlock = lock::unlock,
        )

    /** Mutex сессии в миниатюре: один владелец, без ожидания. */
    private class FakeLock(held: Boolean) {
        val held = AtomicBoolean(held)

        fun tryAcquire(): Boolean = held.compareAndSet(false, true)

        fun unlock() {
            check(held.compareAndSet(true, false)) { "unlock() без захваченного mutex" }
        }
    }
}
