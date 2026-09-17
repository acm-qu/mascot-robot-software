package com.acmqu.acmo.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The 44-byte RIFF header Gemini wants in front of raw PCM. */
object Wav {
    fun pcm16Mono(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + pcm.size)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)                    // fmt chunk size
        out.putShort(1)                   // PCM
        out.putShort(1)                   // mono
        out.putInt(sampleRate)
        out.putInt(sampleRate * 2)        // byte rate
        out.putShort(2)                   // block align
        out.putShort(16)                  // bits per sample
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(pcm.size)
        out.put(pcm)
        return out.array()
    }
}
