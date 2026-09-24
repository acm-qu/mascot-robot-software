package com.acmqu.acmo.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Decides when the mouth should move from the audio itself: the RMS level of each 20 ms
 * window of 16-bit PCM, with hysteresis so the mouth does not flicker between syllables.
 *
 * The output is a list of changes, each "from this byte of the reply, show this level":
 * 0 closed, 1 a small mouth, 2 a big one. Because the player is fed well ahead of what
 * is heard, a gap can be confirmed [HOLD_WINDOWS] later and still be cued at the byte where
 * it began -- that is what makes the mouth close *at* the pause rather than after it.
 * A change is never at byte 0: a cue there would fire before the sound starts.
 *
 * Pure Kotlin, one producer thread at a time, like [AudioOut.play].
 */
class MouthGate {
    data class Change(val atByte: Long, val level: Int)

    class Summary(val windows: Int, val openWindows: Int, val changes: Int, val db: String)

    private var fed = 0L            // bytes seen so far
    private var sumSquares = 0.0    // of the window in progress
    private var inWindow = 0        // samples in the window in progress
    private var level = 0           // last emitted level
    private var silentRun = 0       // quiet windows in a row while open
    private var silentFrom = 0L     // where that run began
    private var windows = 0
    private var openWindows = 0
    private var changes = 0
    private val histogram = IntArray(100)   // windows per -dBFS bucket, for tuning the thresholds

    /** Whole frames only. Returns the changes decided by these bytes, in byte order. */
    fun feed(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): List<Change> {
        require(length % 2 == 0) { "half a frame: $length bytes" }
        var out: MutableList<Change>? = null
        var i = offset
        val end = offset + length
        while (i < end) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sumSquares += s * s
            inWindow++
            i += 2
            fed += 2
            if (inWindow == WINDOW_SAMPLES) {
                val change = window(sqrt(sumSquares / WINDOW_SAMPLES))
                if (change != null) (out ?: mutableListOf<Change>().also { out = it }).add(change)
                sumSquares = 0.0
                inWindow = 0
            }
        }
        return out ?: emptyList()
    }

    /** A window ended at [fed] with this RMS. */
    private fun window(rms: Double): Change? {
        windows++
        histogram[bucket(rms)]++
        val loud = if (rms >= LOUD_RMS) 2 else 1
        if (level == 0) {
            if (rms < OPEN_RMS) return null
            openWindows++
            return emit(fed, loud)
        }
        if (rms < CLOSE_RMS) {
            if (silentRun == 0) silentFrom = fed - WINDOW_BYTES
            silentRun++
            if (silentRun < HOLD_WINDOWS) {
                openWindows++   // still shown open
                return null
            }
            openWindows -= HOLD_WINDOWS - 1   // those were not, after all
            silentRun = 0
            return emit(silentFrom, 0)
        }
        silentRun = 0
        openWindows++
        return if (loud != level) emit(fed, loud) else null
    }

    private fun emit(atByte: Long, newLevel: Int): Change {
        level = newLevel
        changes++
        return Change(atByte, newLevel)
    }

    private fun bucket(rms: Double): Int {
        if (rms < 1.0) return histogram.size - 1
        val db = -20 * log10(rms / 32768.0)
        return db.toInt().coerceIn(0, histogram.size - 1)
    }

    /** What the reply looked like, for the log: window counts and the level's 10th, 50th and 90th percentiles. */
    fun summary(): Summary {
        fun percentile(p: Int): Int {
            var need = (windows * p + 99) / 100
            for (b in histogram.indices) {
                need -= histogram[b]
                if (need <= 0) return b
            }
            return histogram.size - 1
        }
        val db = if (windows == 0) "-" else "-${percentile(10)}/-${percentile(50)}/-${percentile(90)} dBFS (loudest/median/quietest)"
        return Summary(windows, openWindows, changes, db)
    }

    companion object {
        /** 20 ms of AudioOut's 24 kHz mono 16-bit PCM. */
        const val WINDOW_SAMPLES = AudioOut.SAMPLE_RATE / 50
        const val WINDOW_BYTES = WINDOW_SAMPLES * AudioOut.BYTES_PER_FRAME.toInt()
        /** A closed mouth opens above this (about -42 dBFS)... */
        const val OPEN_RMS = 260.0
        /** ...and an open one closes below this (about -50 dBFS), which keeps it from flickering. */
        const val CLOSE_RMS = 104.0
        /** Above this the mouth is wide (about -26 dBFS). */
        const val LOUD_RMS = 1640.0
        /** Quiet windows in a row before the mouth closes: 160 ms, longer than a gap between syllables. */
        const val HOLD_WINDOWS = 8
    }
}
