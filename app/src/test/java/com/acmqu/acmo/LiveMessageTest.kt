package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.gemini.Faces
import com.acmqu.acmo.gemini.LiveMessage
import com.acmqu.acmo.gemini.Sentence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages as gemini-3.8-live actually sent them on 2026-09-17. */
class LiveMessageTest {

    @Test
    fun `setup complete and the empty frames the server sends`() {
        assertTrue(LiveMessage.parse("""{"setupComplete": {}}""").setupComplete)
        val empty = LiveMessage.parse("{}")
        assertFalse(empty.setupComplete)
        assertTrue(empty.audio.isEmpty())
        assertFalse(empty.turnComplete)
        assertEquals(LiveMessage(), LiveMessage.parse("""{"serverContent": {}}"""))
    }

    @Test
    fun `audio chunks are the base64 of the model's voice`() {
        val m = LiveMessage.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAA"}}]}}}""",
        )
        assertEquals(listOf("AAAA"), m.audio)
        assertFalse(m.turnComplete)
    }

    @Test
    fun `the face tool call carries the planned feelings`() {
        val m = LiveMessage.parse(
            """{"toolCall":{"functionCalls":[{"name":"set_faces","args":{"feelings":["happy","sad","excited"]},"id":"function-call-1"}]}}""",
        )
        assertEquals(1, m.functionCalls.size)
        assertEquals("function-call-1", m.functionCalls[0].id)
        assertEquals("set_faces", m.functionCalls[0].name)
        assertEquals(listOf(Expression.HAPPY, Expression.SAD, Expression.EXCITED), Faces.parse(m.functionCalls[0].args))
    }

    @Test
    fun `unknown feelings become happy and no feelings is one happy`() {
        assertEquals(listOf(Expression.HAPPY, Expression.ANGRY), Faces.parse(JSONObject("""{"feelings":["giddy","ANGRY"]}""")))
        assertEquals(listOf(Expression.HAPPY), Faces.parse(JSONObject("""{"feelings":[]}""")))
        assertEquals(listOf(Expression.HAPPY), Faces.parse(JSONObject("{}")))
    }

    @Test
    fun `transcripts, turn ends and interruptions`() {
        val m = LiveMessage.parse("""{"serverContent":{"outputTranscription":{"text":"Why do programmers "}}}""")
        assertEquals("Why do programmers ", m.outputTranscript)
        assertNull(m.inputTranscript)
        val heard = LiveMessage.parse("""{"serverContent":{"inputTranscription":{"text":"Hey ACMO"}}}""")
        assertEquals("Hey ACMO", heard.inputTranscript)
        val end = LiveMessage.parse("""{"serverContent":{"generationComplete":true,"turnComplete":true}}""")
        assertTrue(end.generationComplete)
        assertTrue(end.turnComplete)
        assertTrue(LiveMessage.parse("""{"serverContent":{"interrupted":true}}""").interrupted)
    }

    @Test
    fun `resumption handles and go away`() {
        val m = LiveMessage.parse("""{"sessionResumptionUpdate":{"newHandle":"b305c5eb","resumable":true}}""")
        assertEquals("b305c5eb", m.resumptionHandle)
        assertNull(LiveMessage.parse("""{"sessionResumptionUpdate":{"newHandle":"x","resumable":false}}""").resumptionHandle)
        assertEquals(59_000L, LiveMessage.parse("""{"goAway":{"timeLeft":"59s"}}""").goAwayMs)
        assertEquals(500L, LiveMessage.parseDuration("0.5s"))
        assertNull(LiveMessage.parseDuration(""))
    }

    @Test
    fun `an error message is surfaced`() {
        val m = LiveMessage.parse("""{"error":{"code":429,"message":"You exceeded your current quota","status":"RESOURCE_EXHAUSTED"}}""")
        assertEquals("You exceeded your current quota", m.error)
    }

    @Test
    fun `sentences end on english and arabic punctuation`() {
        assertTrue(Sentence.endsIn(" attracts bugs!"))
        assertTrue(Sentence.endsIn("prefer dark mode?"))
        assertTrue(Sentence.endsIn(" ما أحفظ شغلي."))
        assertTrue(Sentence.endsIn("ليش المبرمج بيكره الطبيعة؟ "))
        assertFalse(Sentence.endsIn(" Because light"))
        assertFalse(Sentence.endsIn(""))
    }
}
