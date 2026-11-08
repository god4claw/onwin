package com.example.speech.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityMaskingTest {

    @Test
    fun `numbers are hidden behind placeholders`() {
        val masked = EntityMasking.mask("в 2026 году рост 5 %")

        assertEquals("в [[0]] году рост [[1]]", masked.text)
    }

    @Test
    fun `restore brings the original numbers back`() {
        val masked = EntityMasking.mask("в 2026 году рост 5 %")

        assertEquals("в 2026 году рост 5 %", masked.restore("в [[0]] году рост [[1]]"))
    }

    @Test
    fun `lost placeholder is rejected`() {
        val masked = EntityMasking.mask("в 2026 году рост 5 %")

        assertNull(masked.restore("в [[0]] году рост"))
    }

    @Test
    fun `invented placeholder is rejected`() {
        val masked = EntityMasking.mask("в 2026 году")

        assertNull(masked.restore("в [[0]] году [[7]]"))
    }

    @Test
    fun `text without numbers passes through unchanged`() {
        val masked = EntityMasking.mask("привет как дела")

        assertEquals("привет как дела", masked.text)
        assertEquals("привет, как дела?", masked.restore("привет, как дела?"))
    }

    @Test
    fun `decimal numbers with separator are masked whole`() {
        val masked = EntityMasking.mask("итого 1,5 миллиона")

        assertEquals("итого [[0]] миллиона", masked.text)
        assertEquals("итого 1,5 миллиона", masked.restore(masked.text))
    }

    @Test
    fun `text without numbers cannot invent a placeholder`() {
        val masked = EntityMasking.mask("привет как дела")

        assertNull(masked.restore("привет [[0]] дела"))
        assertTrue(masked.restore("привет как дела") != null)
    }
}
