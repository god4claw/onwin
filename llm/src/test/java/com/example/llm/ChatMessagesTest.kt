package com.example.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagesTest {

    @Test
    fun json_hasSystemAndUserMessages() {
        assertEquals(
            """[{"role":"system","content":"Ты редактор."},{"role":"user","content":"Фраза: придёт завтра"}]""",
            ChatMessages.json("Ты редактор.", "Фраза: придёт завтра"),
        )
    }

    @Test
    fun blankSystem_isOmitted() {
        val json = ChatMessages.json("", "текст")

        assertFalse(json.contains("system"))
        assertEquals("""[{"role":"user","content":"текст"}]""", json)
    }

    @Test
    fun escapesQuotesBackslashesAndNewlines() {
        val json = ChatMessages.json("", "он сказал \"да\"\nи ушёл\\домой")

        assertTrue(json.contains("""он сказал \"да\"\nи ушёл\\домой"""))
    }

    @Test
    fun escapesControlCharacters() {
        assertEquals("""a\u0001b""", ChatMessages.escape("a\u0001b"))
    }
}
