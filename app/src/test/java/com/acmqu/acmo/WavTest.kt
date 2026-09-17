package com.acmqu.acmo

import com.acmqu.acmo.voice.Wav
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test
    fun `header describes 16 kHz mono pcm and the payload follows it`() {
        val pcm = ByteArray(3200) { it.toByte() }
        val wav = Wav.pcm16Mono(pcm, 16000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt())        // PCM
        assertEquals(1, b.getShort(22).toInt())        // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))              // byte rate
        assertEquals(2, b.getShort(32).toInt())        // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, b.getInt(40))
        assertEquals(pcm[100], wav[144])
    }
}
