package com.example.speech.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionGuardTest {

    @Test
    fun `fixing a couple of typos is accepted`() {
        assertTrue(CorrectionGuard.accepts("привет как дила", "привет как дела"))
    }

    @Test
    fun `punctuation and case are not counted as rewrites`() {
        assertTrue(CorrectionGuard.accepts("Привет как дела", "привет, как дела?"))
    }

    @Test
    fun `rewritten sentence is rejected`() {
        assertFalse(
            CorrectionGuard.accepts(
                "привет как дела",
                "здравствуйте, как ваши дела сегодня",
            ),
        )
    }

    @Test
    fun `empty answer is rejected`() {
        assertFalse(CorrectionGuard.accepts("привет как дела", "   "))
        assertFalse(CorrectionGuard.accepts("   ", "привет"))
    }

    @Test
    fun `exactly ten percent of characters is the boundary`() {
        val source = "а".repeat(100)
        val tenEdits = "б".repeat(10) + "а".repeat(90)
        val elevenEdits = "б".repeat(11) + "а".repeat(89)

        assertTrue(CorrectionGuard.accepts(source, tenEdits))
        assertFalse(CorrectionGuard.accepts(source, elevenEdits))
    }

    @Test
    fun `short phrase allows at least one changed character`() {
        // 5 символов × 10 % = 0, но правка одной буквы должна проходить.
        assertTrue(CorrectionGuard.accepts("кот", "код"))
        assertFalse(CorrectionGuard.accepts("кот", "пёс"))
    }

    @Test
    fun `levenshtein counts substitutions insertions and deletions`() {
        assertEquals(0, CorrectionGuard.levenshtein("кот", "кот"))
        assertEquals(1, CorrectionGuard.levenshtein("кот", "код"))
        assertEquals(1, CorrectionGuard.levenshtein("кот", "коты"))
        assertEquals(1, CorrectionGuard.levenshtein("коты", "кот"))
        assertEquals(3, CorrectionGuard.levenshtein("", "кот"))
    }

    @Test
    fun `normalize collapses whitespace and lowercases`() {
        assertEquals("привет как дела", CorrectionGuard.normalize("  Привет   Как\tДела "))
    }
}
