package com.acmqu.acmo.voice

import kotlin.math.PI
import kotlin.math.cos

/**
 * ACMO's robotic timbre: a ring modulator over the 16-bit PCM on its way to [AudioOut].
 * Each sample is multiplied by a low carrier tone, so the voice picks up the metallic,
 * inharmonic buzz that reads as "robot". [mix] blends the effect with the dry voice --
 * 0 is untouched, 1 is full ring modulation -- because keeping some dry signal in keeps
 * the words intelligible on a public-facing mascot.
 *
 * It is a pure per-sample transform: it changes what each sample *is*, never how many there
 * are. That is deliberate. [AudioOut] cues the face off byte offsets into this same stream,
 * so anything that added or dropped samples would slide the mouth out of step with the voice.
 *
 * The carrier's phase is carried across calls by a running sample count, so chunk boundaries
 * -- the audio arrives from the network in pieces -- do not click. One producer thread at a
 * time, like [AudioOut.play] and [MouthGate.feed].
 */
class VoiceEffect(
    sampleRate: Int = AudioOut.SAMPLE_RATE,
    carrierHz: Double = AudioOut.ROBOT_CARRIER_HZ,
    private val mix: Double = AudioOut.ROBOT_MIX,
) {
    /** Radians of carrier per sample. */
    private val step = 2.0 * PI * carrierHz / sampleRate

    /** Samples processed so far; the carrier's phase is [n] * [step]. */
    private var n = 0L

    /** Ring-modulates [length] bytes (whole 16-bit little-endian frames) from [offset], in place. */
    fun process(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        require(length % 2 == 0) { "half a frame: $length bytes" }
        if (mix == 0.0) return
        var i = offset
        val end = offset + length
        while (i < end) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            // (1 - mix) keeps a share of the dry voice; mix * cos is the ring modulation. cos is in
            // [-1, 1], so the whole factor is in [1 - 2*mix, 1] and can never push a sample past its
            // own magnitude -- the clamp below is belt-and-braces for mix values outside 0..1.
            val factor = (1.0 - mix) + mix * cos(n * step)
            var v = (s * factor).toInt()
            if (v > MAX) v = MAX else if (v < MIN) v = MIN
            pcm[i] = (v and 0xFF).toByte()
            pcm[i + 1] = ((v shr 8) and 0xFF).toByte()
            n++
            i += 2
        }
    }

    companion object {
        private const val MAX = 32767
        private const val MIN = -32768
    }
}
