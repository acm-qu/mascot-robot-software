package com.acmqu.acmo

import com.acmqu.acmo.claude.Answer
import com.acmqu.acmo.claude.AnswerStream
import com.acmqu.acmo.claude.Claude
import com.acmqu.acmo.face.Expression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeTest {
    @Test
    fun `the JSON answer becomes faces and text`() {
        assertEquals(
            Answer(listOf(Expression.HAPPY, Expression.SAD), "Hi there! Sorry."),
            Claude.parse("""{"feelings":["happy","sad"],"text":" Hi there! Sorry. "}"""),
        )
    }

    @Test
    fun `no feelings is one happy face`() {
        assertEquals(listOf(Expression.HAPPY), Claude.parse("""{"feelings":[],"text":"Hi."}""").feelings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `no text is a failure`() {
        Claude.parse("""{"feelings":["happy"],"text":"  "}""")
    }

    /** Feeds [json] in pieces of [size] characters; returns what the stream handed on, in order. */
    private fun stream(json: String, size: Int): List<String> {
        val events = mutableListOf<String>()
        val s = AnswerStream({ f -> events += "faces:" + f.joinToString(",") { it.label } }, { t -> events += t })
        json.chunked(size).forEach(s::feed)
        assertEquals(json, s.whole)
        return events
    }

    @Test
    fun `the streamed text comes out whole and unescaped however it is split`() {
        val json = """{"feelings":["happy","excited"],"text":"Hi! I said \"hey\"\\ \u0645\u0631\u062d\u0628\u0627 \ud83d\ude00."}"""
        val expected = "Hi! I said \"hey\"\\ مرحبا 😀."
        assertEquals(expected, Claude.parse(json).text)
        for (size in 1..json.length) {
            val events = stream(json, size)
            assertEquals("split $size", "faces:happy,excited", events.first())
            assertEquals("split $size", expected, events.drop(1).joinToString(""))
        }
    }

    @Test
    fun `spaces around the key are fine`() {
        val events = stream("""{"feelings": ["sad"], "text": "Oh no."}""", 3)
        assertEquals(listOf("faces:sad", "Oh no."), listOf(events.first(), events.drop(1).joinToString("")))
    }

    @Test
    fun `text before the feelings streams with no faces - they come from the whole answer`() {
        val json = """{"text":"Hello there.","feelings":["angry"]}"""
        val events = stream(json, 4)
        assertTrue(events.none { it.startsWith("faces:") })
        assertEquals("Hello there.", events.joinToString(""))
        assertEquals(listOf(Expression.ANGRY), Claude.parse(json).feelings)
    }
}
