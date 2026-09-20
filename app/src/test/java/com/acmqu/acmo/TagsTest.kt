package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.FaceTag
import com.acmqu.acmo.remote.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio tags in a line: which ones change the face, and where they are. */
class TagsTest {

    @Test
    fun `every label names its face, and so do the extra words`() {
        for (e in Expression.entries) assertEquals(e, Tags.WORDS[e.label])
        assertEquals(Expression.HAPPY, Tags.WORDS["laughs"])
        assertEquals(Expression.HAPPY, Tags.WORDS["giggles"])
        assertEquals(Expression.HAPPY, Tags.WORDS["cheerful"])
        assertEquals(Expression.SAD, Tags.WORDS["crying"])
        assertEquals(Expression.SAD, Tags.WORDS["gloomy"])
        assertEquals(Expression.SAD, Tags.WORDS["disappointed"])
        assertEquals(Expression.ANGRY, Tags.WORDS["shouting"])
        assertEquals(Expression.ANGRY, Tags.WORDS["furious"])
        assertEquals(Expression.ANGRY, Tags.WORDS["growls"])
        assertEquals(Expression.ANNOYED, Tags.WORDS["sarcastic"])
        assertEquals(Expression.ANNOYED, Tags.WORDS["groans"])
        assertEquals(Expression.ANNOYED, Tags.WORDS["frustrated"])
        assertEquals(Expression.SURPRISED, Tags.WORDS["gasps"])
        assertEquals(Expression.SURPRISED, Tags.WORDS["shocked"])
        assertEquals(Expression.SURPRISED, Tags.WORDS["amazed"])
        assertEquals(Expression.EXCITED, Tags.WORDS["thrilled"])
        assertEquals(Expression.EXCITED, Tags.WORDS["enthusiastic"])
        assertEquals(Expression.EXCITED, Tags.WORDS["energetic"])
        assertEquals(Expression.PASSIONATE, Tags.WORDS["loving"])
        assertEquals(Expression.PASSIONATE, Tags.WORDS["romantic"])
        assertEquals(Expression.PASSIONATE, Tags.WORDS["dramatic"])
        // Three extra words for every face but idle, which has only its label.
        for (e in Expression.entries) {
            val expected = if (e == Expression.IDLE) 1 else 4
            assertEquals(e.label, expected, Tags.WORDS.values.count { it == e })
        }
    }

    @Test
    fun `face tags come in order, with their exact text and where they start`() {
        val tags = Tags.faces("[excited] We won! [sad] But the pizza is gone. [laughs] Kidding.")
        assertEquals(
            listOf(
                FaceTag("[excited]", 0, Expression.EXCITED),
                FaceTag("[sad]", 18, Expression.SAD),
                FaceTag("[laughs]", 47, Expression.HAPPY),
            ),
            tags,
        )
    }

    @Test
    fun `the word is matched trimmed and case-insensitively, the text is kept as typed`() {
        val tags = Tags.faces("[ Sad ] oh. [LAUGHS] ha.")
        assertEquals(listOf(Expression.SAD, Expression.HAPPY), tags.map { it.feeling })
        assertEquals("[ Sad ]", tags[0].text)
        assertEquals("[LAUGHS]", tags[1].text)
    }

    @Test
    fun `a voice-only tag is not a face tag, but still makes the line expressive`() {
        val text = "[whispers] come closer. [sighs]"
        assertTrue(Tags.faces(text).isEmpty())
        assertTrue(Tags.hasTags(text))
        assertTrue(Tags.hasTags("[laughs harder] no way"))
        assertFalse(Tags.hasTags("No tags here, just [ a bracket that never closes"))
        assertFalse(Tags.hasTags("Plain text."))
    }

    @Test
    fun `broken brackets are text -- nested ones leave the inner pair as the tag`() {
        assertTrue(Tags.faces("[sad").isEmpty())
        assertTrue(Tags.faces("sad]").isEmpty())
        assertTrue(Tags.faces("[]").isEmpty())
        assertFalse(Tags.hasTags("[]"))
        assertTrue(Tags.faces("[sad\nface]").isEmpty())
        assertFalse(Tags.hasTags("[sad\nface]"))
        assertTrue(Tags.faces("[" + "x".repeat(41) + "]").isEmpty())
        assertEquals(listOf(FaceTag("[sad]", 1, Expression.SAD)), Tags.faces("[[sad]]"))
    }

    @Test
    fun `indexes count code points, the way ElevenLabs counts characters`() {
        // An emoji is two UTF-16 units but one character to ElevenLabs.
        val text = "Hi 👋 there! [sad] Bye."
        val tag = Tags.faces(text).single()
        assertEquals(12, tag.index)
        assertEquals(13, text.indexOf("[sad]"))   // what a UTF-16 index would have said
    }

    @Test
    fun `a repeated tag is reported each time`() {
        val tags = Tags.faces("[sad] one. [sad] two.")
        assertEquals(listOf(0, 11), tags.map { it.index })
    }
}
