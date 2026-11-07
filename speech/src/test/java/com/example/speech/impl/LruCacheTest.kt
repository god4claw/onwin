package com.example.speech.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LruCacheTest {

    @Test
    fun `creates value once per key`() {
        val cache = LruCache<String, String>(maxEntries = 2)
        var created = 0

        val first = cache.getOrCreate("a") {
            created++
            "A"
        }
        val second = cache.getOrCreate("a") {
            created++
            "B"
        }

        assertEquals(1, created)
        assertSame(first, second)
        assertEquals(1, cache.size)
    }

    @Test
    fun `evicts least recently used and releases it`() {
        val evicted = ArrayList<String>()
        val cache = LruCache<String, String>(maxEntries = 2, onEvict = evicted::add)

        cache.getOrCreate("a") { "A" }
        cache.getOrCreate("b") { "B" }
        // Обращение к "a" делает "b" самым старым.
        cache.getOrCreate("a") { error("не должно вызываться") }
        cache.getOrCreate("c") { "C" }

        assertEquals(listOf("B"), evicted)
        assertEquals(2, cache.size)
    }

    @Test
    fun `clear releases every value`() {
        val evicted = ArrayList<String>()
        val cache = LruCache<String, String>(maxEntries = 3, onEvict = evicted::add)
        cache.getOrCreate("a") { "A" }
        cache.getOrCreate("b") { "B" }

        cache.clear()

        assertEquals(0, cache.size)
        assertEquals(listOf("A", "B"), evicted)
    }

    @Test
    fun `rejects non positive capacity`() {
        val error = runCatching { LruCache<String, String>(maxEntries = 0) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
