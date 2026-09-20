package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.Entry
import com.acmqu.acmo.remote.Failure
import com.acmqu.acmo.remote.Line
import com.acmqu.acmo.remote.Said
import com.acmqu.acmo.remote.Say
import com.acmqu.acmo.remote.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The body of POST /say, and the JSON the other routes answer with. */
class LineTest {

    @Test
    fun `reads the text, the feeling and now`() {
        val s = Say.parse("""{"text": "Hello there!", "feeling": "excited", "now": true}""")
        assertEquals(Line("Hello there!", Expression.EXCITED), s.line)
        assertTrue(s.now)
    }

    @Test
    fun `feeling defaults to happy, now to false, and the text is trimmed`() {
        val s = Say.parse("""{"text": "  Hi  "}""")
        assertEquals(Line("Hi", Expression.HAPPY), s.line)
        assertFalse(s.now)
    }

    @Test
    fun `feelings are the face's labels, whatever the case or spacing`() {
        assertEquals(Expression.PASSIONATE, Say.parse("""{"text": "x", "feeling": " Passionate "}""").line.feeling)
        assertEquals(Expression.IDLE, Say.parse("""{"text": "x", "feeling": "IDLE"}""").line.feeling)
    }

    @Test
    fun `the longest line is accepted`() {
        val text = "a".repeat(Line.MAX_CHARS)
        assertEquals(text, Say.parse("""{"text": "$text"}""").line.text)
    }

    @Test
    fun `refuses what cannot be said, with the reason`() {
        assertRefused("""{"text": ""}""", "text is empty")
        assertRefused("""{"text": "   "}""", "text is empty")
        assertRefused("""{"feeling": "happy"}""", "text is empty")
        assertRefused("""{"text": "${"a".repeat(Line.MAX_CHARS + 1)}"}""", "text is longer than 2000 characters")
        assertRefused(
            """{"text": "x", "feeling": "smug"}""",
            "unknown feeling \"smug\"; one of idle surprised sad happy angry passionate annoyed excited",
        )
        assertRefused("not json", "the body is not JSON")
        assertRefused("", "the body is not JSON")
        assertRefused("[1, 2]", "the body is not JSON")
    }

    private fun assertRefused(body: String, why: String) {
        try {
            Say.parse(body)
            fail("accepted: $body")
        } catch (e: IllegalArgumentException) {
            assertEquals(why, e.message)
        }
    }

    @Test
    fun `said is id and queued`() {
        val json = Said(7, 2).toJson()
        assertEquals(7, json.getInt("id"))
        assertEquals(2, json.getInt("queued"))
    }

    @Test
    fun `a quiet snapshot has nulls, not missing keys`() {
        val json = Snapshot(Brain.State.IDLE, null, emptyList(), null).toJson()
        assertEquals("idle", json.getString("state"))
        assertTrue(json.has("line"))
        assertTrue(json.isNull("line"))
        assertEquals(0, json.getJSONArray("queue").length())
        assertTrue(json.has("error"))
        assertTrue(json.isNull("error"))
    }

    @Test
    fun `a busy snapshot lists the line, the queue and the error`() {
        val json = Snapshot(
            Brain.State.SPEAKING,
            Entry(3, Line("Hi", Expression.HAPPY)),
            listOf(Entry(4, Line("Bye", Expression.SAD)), Entry(5, Line("Wait", Expression.ANGRY))),
            Failure(2, "ElevenLabs 401: Invalid API key"),
        ).toJson()
        assertEquals("speaking", json.getString("state"))
        val line = json.getJSONObject("line")
        assertEquals(3, line.getInt("id"))
        assertEquals("Hi", line.getString("text"))
        assertEquals("happy", line.getString("feeling"))
        val queue = json.getJSONArray("queue")
        assertEquals(2, queue.length())
        assertEquals(4, queue.getJSONObject(0).getInt("id"))
        assertEquals("sad", queue.getJSONObject(0).getString("feeling"))
        assertEquals("Wait", queue.getJSONObject(1).getString("text"))
        val error = json.getJSONObject("error")
        assertEquals(2, error.getInt("id"))
        assertEquals("ElevenLabs 401: Invalid API key", error.getString("message"))
    }
}
