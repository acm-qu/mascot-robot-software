package com.acmqu.acmo

import com.acmqu.acmo.remote.Tags
import com.acmqu.acmo.vision.Arrival
import com.acmqu.acmo.vision.Greetings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The fixed lines ACMO says when someone walks up. */
class GreetingsTest {

    private val all = Greetings.ONE + Greetings.GROUP + Greetings.SMILING

    @Test
    fun `a group gets a group line, even when smiling`() {
        assertEquals(Greetings.GROUP, Greetings.lines(Arrival(2, smiling = true)))
    }

    @Test
    fun `one smiling person gets a smiling line, one other gets a plain one`() {
        assertEquals(Greetings.SMILING, Greetings.lines(Arrival(1, smiling = true)))
        assertEquals(Greetings.ONE, Greetings.lines(Arrival(1, smiling = false)))
    }

    @Test
    fun `the same line never comes twice in a row`() {
        val random = Random(42)
        val one = Arrival(1, false)
        var last: String? = null
        repeat(200) {
            val line = Greetings.pick(one, last, random)
            assertNotEquals(last, line.text)
            assertTrue(line in Greetings.ONE)
            last = line.text
        }
    }

    @Test
    fun `every line is used`() {
        val random = Random(7)
        val seen = (1..200).map { Greetings.pick(Arrival(1, false), null, random) }.toSet()
        assertEquals(Greetings.ONE.toSet(), seen)
    }

    @Test
    fun `no line has an audio tag, so all go to the fast model`() {
        for (line in all) assertFalse(line.text, Tags.hasTags(line.text))
    }

    @Test
    fun `every line invites the wake word`() {
        for (line in all) assertTrue(line.text, line.text.lowercase().contains("hey acmo"))
    }
}
