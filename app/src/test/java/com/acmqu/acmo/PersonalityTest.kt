package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.gemini.Personality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityTest {

    private fun setup(handle: String?) = Personality.setup(handle).getJSONObject("setup")

    @Test
    fun `the face tool enumerates exactly the faces, in order`() {
        val tool = setup(null).getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals(Personality.FACES_TOOL, tool.getString("name"))
        val enum = tool.getJSONObject("parameters").getJSONObject("properties").getJSONObject("feelings")
            .getJSONObject("items").getJSONArray("enum")
        assertEquals(Expression.entries.size, enum.length())
        for (i in 0 until enum.length()) assertEquals(Expression.entries[i].label, enum.getString(i))
    }

    @Test
    fun `audio out, no thinking, both transcriptions`() {
        val s = setup(null)
        assertEquals(Personality.MODEL, s.getString("model"))
        val gen = s.getJSONObject("generationConfig")
        assertEquals("AUDIO", gen.getJSONArray("responseModalities").getString(0))
        assertEquals(0, gen.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertEquals(
            Personality.VOICE,
            gen.getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"),
        )
        assertTrue(s.has("inputAudioTranscription"))
        assertTrue(s.has("outputAudioTranscription"))
        assertEquals(
            Personality.END_OF_SPEECH_MS,
            s.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getInt("silenceDurationMs"),
        )
    }

    @Test
    fun `a resumption handle is passed only when there is one`() {
        assertFalse(setup(null).getJSONObject("sessionResumption").has("handle"))
        assertEquals("abc", setup("abc").getJSONObject("sessionResumption").getString("handle"))
    }

    @Test
    fun `the system prompt names the tool and every feeling`() {
        assertTrue(Personality.SYSTEM.contains(Personality.FACES_TOOL))
        for (e in Expression.entries) assertTrue(e.label, Personality.SYSTEM.contains(e.label))
    }
}
