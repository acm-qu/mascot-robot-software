package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.Cue
import com.acmqu.acmo.remote.FaceCues
import com.acmqu.acmo.remote.FaceTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** From "this character starts at this byte" to "show this face at this byte". */
class FaceCuesTest {

    // "[excited] We won! [sad] But the pizza is gone." -- tags at 0 and 18.
    private val tags = listOf(
        FaceTag("[excited]", 0, Expression.EXCITED),
        FaceTag("[sad]", 18, Expression.SAD),
    )

    /** The byte offsets of [n] characters starting at character [from], 1 000 bytes apart. */
    private fun bytes(from: Int, n: Int) = LongArray(n) { (from + it) * 1000L }

    @Test
    fun `the tag at index 0 is never a cue -- the next one is, once its character is timed`() {
        val cues = FaceCues(tags)
        assertEquals(1, cues.pending)
        assertEquals(emptyList<Cue>(), cues.feed(bytes(0, 18)))   // characters 0..17: the tag at 18 is not timed yet
        assertEquals(1, cues.pending)
        assertEquals(listOf(Cue(18_000, Expression.SAD)), cues.feed(bytes(18, 5)))
        assertEquals(0, cues.pending)
        assertEquals(emptyList<Cue>(), cues.feed(bytes(23, 20)))
    }

    @Test
    fun `a feed may cover several tags, or split a tag anywhere`() {
        val three = tags + FaceTag("[laughs]", 40, Expression.HAPPY)
        // One feed with both tags in it.
        assertEquals(listOf(Cue(18_000, Expression.SAD), Cue(40_000, Expression.HAPPY)), FaceCues(three).feed(bytes(0, 50)))
        // Split: "[sa" in one feed, "d]" in the next. Only the '[' matters, so the cue comes with the first.
        val split = FaceCues(three)
        assertEquals(emptyList<Cue>(), split.feed(bytes(0, 17)))
        assertEquals(listOf(Cue(18_000, Expression.SAD)), split.feed(bytes(17, 4)))   // characters 17..20
        assertEquals(listOf(Cue(40_000, Expression.HAPPY)), split.feed(bytes(21, 30)))
    }

    @Test
    fun `a cue is a whole frame`() {
        val cues = FaceCues(listOf(FaceTag("[sad]", 1, Expression.SAD)))
        assertEquals(listOf(Cue(4_242, Expression.SAD)), cues.feed(longArrayOf(0, 4_243)))
    }

    @Test
    fun `no tags, no cues -- and an empty feed changes nothing`() {
        val none = FaceCues(emptyList())
        assertEquals(0, none.pending)
        assertTrue(none.feed(bytes(0, 10)).isEmpty())
        val some = FaceCues(tags)
        assertTrue(some.feed(longArrayOf()).isEmpty())
        assertEquals(1, some.pending)
    }
}
