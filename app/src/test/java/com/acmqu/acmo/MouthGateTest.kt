package com.acmqu.acmo

import com.acmqu.acmo.voice.MouthGate
import com.acmqu.acmo.voice.MouthGate.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** From PCM to "the mouth opens/closes at this byte": an RMS gate with hysteresis and look-ahead. */
class MouthGateTest {

    private val window = MouthGate.WINDOW_BYTES   // 20 ms

    /** [n] windows of 16-bit little-endian samples all at [amp], so the RMS is |amp|. */
    private fun tone(n: Int, amp: Int): ByteArray {
        val out = ByteArray(n * window)
        var i = 0
        while (i < out.size) {
            out[i] = (amp and 0xFF).toByte()
            out[i + 1] = ((amp shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun MouthGate.feedAll(vararg parts: ByteArray): List<Change> = parts.flatMap { feed(it) }

    @Test
    fun `silence never opens the mouth`() {
        assertEquals(emptyList<Change>(), MouthGate().feed(tone(50, 0)))
    }

    @Test
    fun `speech opens the mouth at the end of its first window, never at byte 0`() {
        assertEquals(listOf(Change(window.toLong(), 2)), MouthGate().feed(tone(3, 3000)))
    }

    @Test
    fun `quiet speech opens small, loud speech opens big`() {
        assertEquals(listOf(Change(window.toLong(), 1)), MouthGate().feed(tone(2, 500)))
        assertEquals(listOf(Change(window.toLong(), 2)), MouthGate().feed(tone(2, 3000)))
    }

    @Test
    fun `a gap shorter than the hold keeps the mouth open`() {
        val gate = MouthGate()
        val changes = gate.feedAll(tone(2, 3000), tone(MouthGate.HOLD_WINDOWS - 1, 0), tone(2, 3000))
        assertEquals(listOf(Change(window.toLong(), 2)), changes)
    }

    @Test
    fun `a gap as long as the hold closes the mouth where the gap began`() {
        val gate = MouthGate()
        val changes = gate.feedAll(tone(2, 3000), tone(MouthGate.HOLD_WINDOWS, 0))
        assertEquals(listOf(Change(window.toLong(), 2), Change(2L * window, 0)), changes)
    }

    @Test
    fun `speech after a closed gap opens again`() {
        val gate = MouthGate()
        val changes = gate.feedAll(tone(1, 3000), tone(MouthGate.HOLD_WINDOWS, 0), tone(1, 3000))
        val reopenAt = (1 + MouthGate.HOLD_WINDOWS + 1).toLong() * window
        assertEquals(listOf(Change(window.toLong(), 2), Change(window.toLong(), 0), Change(reopenAt, 2)), changes)
    }

    @Test
    fun `the level changes between small and big at the end of the window that changed it`() {
        val gate = MouthGate()
        val changes = gate.feedAll(tone(2, 500), tone(2, 3000))
        assertEquals(listOf(Change(window.toLong(), 1), Change(3L * window, 2)), changes)
    }

    @Test
    fun `sound between the close and open thresholds keeps an open mouth open`() {
        val gate = MouthGate()
        val between = (MouthGate.CLOSE_RMS + MouthGate.OPEN_RMS) / 2
        val changes = gate.feedAll(tone(1, 3000), tone(20, between.toInt()))
        assertEquals(listOf(Change(window.toLong(), 2), Change(2L * window, 1)), changes)
    }

    @Test
    fun `sound between the thresholds does not open a closed mouth`() {
        val between = (MouthGate.CLOSE_RMS + MouthGate.OPEN_RMS) / 2
        assertEquals(emptyList<Change>(), MouthGate().feed(tone(20, between.toInt())))
    }

    @Test
    fun `chunk boundaries do not change the result`() {
        val signal = tone(2, 3000) + tone(MouthGate.HOLD_WINDOWS, 0) + tone(3, 500)
        val whole = MouthGate().feed(signal)
        val pieces = MouthGate()
        val got = mutableListOf<Change>()
        var off = 0
        var size = 2
        while (off < signal.size) {
            val n = minOf(size, signal.size - off)
            got += pieces.feed(signal.copyOfRange(off, off + n))
            off += n
            size = (size * 3 + 2) % 1000 + 2   // 2, 8, 26, 80, 242, 728, 188 ... always even
        }
        assertEquals(whole, got)
        assertEquals(3, whole.size)
    }

    @Test
    fun `half a frame is refused`() {
        assertThrows(IllegalArgumentException::class.java) { MouthGate().feed(ByteArray(3)) }
    }

    @Test
    fun `negative samples count by magnitude`() {
        assertEquals(listOf(Change(window.toLong(), 2)), MouthGate().feed(tone(2, -3000)))
    }

    @Test
    fun `the summary counts windows, open windows and changes`() {
        val gate = MouthGate()
        gate.feedAll(tone(2, 3000), tone(MouthGate.HOLD_WINDOWS, 0))
        val s = gate.summary()
        assertEquals(2 + MouthGate.HOLD_WINDOWS, s.windows)
        assertEquals(2, s.openWindows)
        assertEquals(2, s.changes)
    }
}
