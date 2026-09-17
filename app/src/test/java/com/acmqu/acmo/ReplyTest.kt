package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.gemini.GeminiClient
import com.acmqu.acmo.gemini.Reply
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReplyTest {

    /** A real gemini-3.5-flash-lite interaction, as returned on 2026-09-17: a thought step, then the JSON. */
    private val interaction = """
        {"id":"v1_abc","status":"completed","object":"interaction","model":"gemini-3.5-flash-lite",
         "steps":[
           {"signature":"El4KXA","type":"thought"},
           {"type":"model_output","content":[{"type":"text","text":"{\n \"language\": \"en\",\n \"segments\": [\n {\"feeling\": \"excited\", \"text\": \"Hi Hakim! Nice to meet you!\"},\n {\"feeling\": \"happy\", \"text\": \"Why do robots never panic?\"},\n {\"feeling\": \"passionate\", \"text\": \"Nerves of steel!\"}\n ]\n}"}]}
         ]}
    """.trimIndent()

    @Test
    fun `output text skips thought steps and joins model output`() {
        val text = GeminiClient.outputText(JSONObject(interaction))
        assertEquals(true, text.startsWith("{"))
        assertEquals(true, text.contains("Nerves of steel"))
    }

    @Test
    fun `silence yields no text`() {
        val silent = """{"status":"completed","steps":[{"type":"model_output","content":[]}]}"""
        assertEquals("", GeminiClient.outputText(JSONObject(silent)))
    }

    @Test
    fun `reply parses segments in order with their feelings`() {
        val reply = Reply.parse(GeminiClient.outputText(JSONObject(interaction)), "v1_abc")
        assertEquals(3, reply.segments.size)
        assertEquals(Expression.EXCITED, reply.segments[0].feeling)
        assertEquals("Why do robots never panic?", reply.segments[1].text)
        assertEquals(Expression.PASSIONATE, reply.segments[2].feeling)
        assertEquals("en", reply.language)
        assertEquals("v1_abc", reply.interactionId)
    }

    @Test
    fun `unknown feelings fall back to happy and blank segments are dropped`() {
        val reply = Reply.parse(
            """{"language":"ar","segments":[{"feeling":"giddy","text":"hi"},{"feeling":"sad","text":"   "}]}""",
            null,
        )
        assertEquals(1, reply.segments.size)
        assertEquals(Expression.HAPPY, reply.segments[0].feeling)
        assertEquals("ar", reply.language)
        assertNull(reply.interactionId)
    }

    @Test
    fun `a reply with nothing to say is an error`() {
        assertThrows(JSONException::class.java) { Reply.parse("""{"segments":[]}""", null) }
    }

    @Test
    fun `the response schema enumerates exactly the faces`() {
        val schema = com.acmqu.acmo.gemini.Personality.responseFormat().getJSONObject("schema")
        val feelings = schema.getJSONObject("properties").getJSONObject("segments")
            .getJSONObject("items").getJSONObject("properties").getJSONObject("feeling").getJSONArray("enum")
        assertEquals(Expression.entries.size, feelings.length())
        for (i in 0 until feelings.length()) assertEquals(Expression.entries[i].label, feelings.getString(i))
        assertEquals("application/json", com.acmqu.acmo.gemini.Personality.responseFormat().getString("mime_type"))
    }

    @Test
    fun `every expression label round-trips`() {
        for (e in Expression.entries) assertEquals(e, Expression.fromLabel(e.label.uppercase()))
        assertNull(Expression.fromLabel("bored"))
        assertNull(Expression.fromLabel(null))
    }
}
