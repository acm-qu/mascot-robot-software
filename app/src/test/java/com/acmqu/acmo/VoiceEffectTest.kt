package com.acmqu.acmo

import com.acmqu.acmo.voice.VoiceEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/** The robotic voice: a phase-continuous ring modulator over 16-bit little-endian PCM, in place. */
class VoiceEffectTest {

    private val sampleRate = 24000
    private val carrier = 55.0

    private fun le(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (k in samples.indices) {
            out[k * 2] = (samples[k] and 0xFF).toByte()
            out[k * 2 + 1] = ((samples[k] shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(b: ByteArray, k: Int): Int =
        ((b[k * 2 + 1].toInt() shl 8) or (b[k * 2].toInt() and 0xFF)).toShort().toInt()

    @Test
    fun `a half frame is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { VoiceEffect().process(ByteArray(3)) }
    }

    @Test
    fun `mix of zero leaves the audio untouched`() {
        val audio = le(0, 12345, -12345, 32767, -32768, 7)
        val copy = audio.copyOf()
        VoiceEffect(sampleRate, carrier, mix = 0.0).process(audio)
        assertArrayEquals(copy, audio)
    }

    @Test
    fun `full ring modulation multiplies a DC signal by the carrier`() {
        val n = 200
        val dc = 10000
        val audio = le(*IntArray(n) { dc })
        VoiceEffect(sampleRate, carrier, mix = 1.0).process(audio)
        val step = 2.0 * PI * carrier / sampleRate
        for (k in 0 until n) {
            val expected = (dc * cos(k * step)).toInt()
            assertEquals("sample $k", expected, sampleAt(audio, k))
        }
    }

    @Test
    fun `the carrier phase carries across calls, so splitting a buffer matches processing it whole`() {
        val samples = IntArray(64) { (it * 517 % 5000) - 2500 }   // a spread of positive and negative values
        val whole = le(*samples)
        val split = whole.copyOf()

        VoiceEffect(sampleRate, carrier, mix = 0.7).process(whole)

        val effect = VoiceEffect(sampleRate, carrier, mix = 0.7)
        effect.process(split, 0, 20)              // first ten frames
        effect.process(split, 20, split.size - 20)  // the rest, phase continued
        assertArrayEquals(whole, split)
    }

    @Test
    fun `the effect never pushes a sample past its own magnitude`() {
        val loud = IntArray(500) { if (it % 2 == 0) 32767 else -32768 }
        val audio = le(*loud)
        VoiceEffect(sampleRate, carrier, mix = 0.6).process(audio)
        for (k in loud.indices) {
            assertTrue("sample $k in range", sampleAt(audio, k) in -32768..32767)
        }
    }
}
