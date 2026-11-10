package com.acmqu.acmo

import com.acmqu.acmo.vision.Arrival
import com.acmqu.acmo.vision.FacePresence
import com.acmqu.acmo.vision.FacePresence.Companion.COOLDOWN_MS
import com.acmqu.acmo.vision.FacePresence.Companion.DWELL_MS
import com.acmqu.acmo.vision.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** From faces per frame to "someone arrived": close, looking, staying -- once per person. */
class FacePresenceTest {

    /** A face close enough and looking straight at ACMO, unless told otherwise. */
    private fun face(id: Int?, width: Float = 0.2f, yaw: Float = 0f, pitch: Float = 0f, smiling: Float? = null) =
        Seen(id, width, yaw, pitch, smiling)

    /** Every arrival from frames every [step] ms over [from, to], each frame showing [faces]. */
    private fun FacePresence.run(
        from: Long,
        to: Long,
        vararg faces: Seen,
        busy: Boolean = false,
        step: Long = 200,
    ): List<Pair<Long, Arrival>> {
        val out = mutableListOf<Pair<Long, Arrival>>()
        var t = from
        while (t <= to) {
            feed(faces.toList(), t, busy)?.let { out += t to it }
            t += step
        }
        return out
    }

    @Test
    fun `a face that stays long enough arrives once, at the dwell`() {
        val got = FacePresence().run(0, 10_000, face(1))
        assertEquals(listOf(DWELL_MS to Arrival(1, false)), got)
    }

    @Test
    fun `a face that leaves before the dwell never arrives`() {
        val p = FacePresence()
        assertEquals(emptyList<Any>(), p.run(0, 600, face(1)))
        assertEquals(emptyList<Any>(), p.run(800, 5_000))   // gone
    }

    @Test
    fun `a face turned away or too far never arrives`() {
        assertEquals(emptyList<Any>(), FacePresence().run(0, 5_000, face(1, yaw = 40f)))
        assertEquals(emptyList<Any>(), FacePresence().run(0, 5_000, face(1, pitch = -30f)))
        assertEquals(emptyList<Any>(), FacePresence().run(0, 5_000, face(1, width = 0.05f)))
    }

    @Test
    fun `a face without a tracking id never arrives`() {
        assertEquals(emptyList<Any>(), FacePresence().run(0, 5_000, face(null)))
    }

    @Test
    fun `a short dropout keeps the dwell, a long one starts it over`() {
        val short = FacePresence()
        short.run(0, 400, face(1))
        assertEquals(listOf(1_600L to Arrival(1, false)), short.run(1_600, 3_000, face(1)))   // 1.2 s gap

        val long = FacePresence()
        long.run(0, 400, face(1))
        // A 2 s gap: the dwell restarts at 2 400 and completes at 3 200.
        assertEquals(listOf(3_200L to Arrival(1, false)), long.run(2_400, 4_000, face(1)))
    }

    @Test
    fun `the same person is never greeted twice`() {
        val p = FacePresence()
        p.run(0, 2_000, face(1))
        p.run(2_200, 5_000)                                                          // steps away
        assertEquals(emptyList<Any>(), p.run(30_000, 40_000, face(1)))              // and back, past the cooldown
    }

    @Test
    fun `a second person within the cooldown waits for it`() {
        val p = FacePresence()
        p.run(0, 2_000, face(1))
        // Face 2 has stayed long enough by 5 800, but the first greeting was at 800.
        assertEquals(listOf(DWELL_MS + COOLDOWN_MS to Arrival(1, false)), p.run(5_000, 25_000, face(2)))
    }

    @Test
    fun `a group is greeted once, counted, and smiling if anyone is`() {
        val got = FacePresence().run(0, 10_000, face(1), face(2, smiling = 0.9f), face(3, smiling = 0.1f))
        assertEquals(listOf(DWELL_MS to Arrival(3, true)), got)
    }

    @Test
    fun `a face that does not qualify is not counted in the group`() {
        val got = FacePresence().run(0, 2_000, face(1), face(2, yaw = 60f))
        assertEquals(listOf(DWELL_MS to Arrival(1, false)), got)
    }

    @Test
    fun `faces seen while busy are never greeted afterwards`() {
        val p = FacePresence()
        p.run(0, 5_000, face(1), busy = true)
        assertEquals(emptyList<Any>(), p.run(5_200, 30_000, face(1)))
    }

    @Test
    fun `being busy stops a greeting that was part-way`() {
        val p = FacePresence()
        p.run(0, 400, face(1))
        assertNull(p.feed(listOf(face(1)), 600, busy = true))
        assertEquals(emptyList<Any>(), p.run(800, 5_000, face(1)))
    }

    @Test
    fun `a newcomer after a busy spell is greeted`() {
        val p = FacePresence()
        p.run(0, 5_000, face(1), busy = true)
        assertEquals(listOf(6_000L to Arrival(2, false)), p.run(5_200, 10_000, face(1), face(2)))
    }

    @Test
    fun `reset restarts the dwell but keeps who was greeted`() {
        val p = FacePresence()
        p.run(0, 600, face(1))
        p.reset()
        // Without the reset this would arrive at 800; the dwell starts over there instead.
        assertEquals(listOf(1_600L to Arrival(1, false)), p.run(800, 2_000, face(1)))
        p.reset()
        assertEquals(emptyList<Any>(), p.run(2_200, 30_000, face(1)))
    }

    @Test
    fun `a greeted face unseen for a long time is forgotten and may be greeted again`() {
        val p = FacePresence()
        p.run(0, 1_000, face(1))
        p.run(1_200, 1_200)                                         // last seen at 1 000
        val back = 1_000 + FacePresence.FORGET_MS + 1_000
        assertEquals(listOf(back + DWELL_MS to Arrival(1, false)), p.run(back, back + 2_000, face(1)))
    }
}
