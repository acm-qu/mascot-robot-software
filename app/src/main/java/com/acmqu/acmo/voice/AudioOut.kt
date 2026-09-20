package com.acmqu.acmo.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.PriorityQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays one reply: the model's 24 kHz mono 16-bit PCM, chunk by chunk as it
 * arrives, on an AudioTrack fed by its own thread. A cue runs on the main
 * thread once playback has passed a byte offset -- that is how the face
 * changes between sentences.
 *
 * [onStart] fires (main thread) when the first chunk plays; [onFinish] once
 * everything queued before [finish] has been heard, or [cancel] threw it away.
 */
class AudioOut(
    private val onStart: (AudioOut) -> Unit,
    private val onFinish: (AudioOut) -> Unit,
) {
    private class Cue(val atByte: Long, val action: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val cues = PriorityQueue<Cue>(compareBy { it.atByte })   // guarded by itself
    @Volatile private var track: AudioTrack? = null
    @Volatile private var cancelled = false
    private var startedAt = 0L   // uptime when the track started; the audio thread's
    private var half: Byte? = null   // an odd byte carried into the next chunk; one producer thread at a time

    init {
        Thread(::loop, "acmo-audio-out").start()
    }

    /** Any thread, one producer at a time. */
    fun play(pcm: ByteArray) {
        if (cancelled || pcm.isEmpty()) return
        // Whole frames only: AudioTrack.write never takes a half frame, and the loop below would spin on
        // it. An odd byte is carried into the next chunk -- dropped, it would byte-swap everything after it.
        val h = half
        val data = if (h == null) pcm else byteArrayOf(h) + pcm
        val whole = data.size and 1.inv()
        half = if (whole < data.size) data[whole] else null
        if (whole > 0) queue.put(if (whole == data.size) data else data.copyOf(whole))
    }

    /** Runs [action] on the main thread when playback reaches [atByte] of this reply. Nothing after a cancel. */
    fun cue(atByte: Long, action: () -> Unit) {
        if (cancelled) return
        synchronized(cues) { cues.add(Cue(atByte, action)) }
    }

    /** No more audio is coming: finish once what is queued has played. */
    fun finish() {
        queue.put(END)
    }

    /** Stop now and drop whatever is queued. */
    fun cancel() {
        cancelled = true
        queue.clear()
        queue.put(END)
        // Unblocks a write() that is waiting for buffer space.
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
        }
    }

    private fun loop() {
        val t = open()
        if (t == null) {
            main.post { onFinish(this) }
            return
        }
        track = t
        var written = 0L
        var started = false
        try {
            while (!cancelled) {
                val chunk = queue.poll(20, TimeUnit.MILLISECONDS)
                if (chunk === END) break
                if (chunk == null) {
                    fireCues(t)
                    continue
                }
                if (!started) {
                    started = true
                    // Started with data in hand, so the track does not run empty while the first bytes are on their way.
                    t.play()
                    startedAt = SystemClock.uptimeMillis()
                    main.post { onStart(this) }
                }
                var off = 0
                while (off < chunk.size && !cancelled) {
                    // Small slices so the cues are checked every 20 ms even while write() blocks.
                    val n = t.write(chunk, off, minOf(SLICE_BYTES, chunk.size - off))
                    // 0 means the track is stopped or paused (a cancel), never "try again": spinning here would never end.
                    if (n <= 0) throw IllegalStateException("AudioTrack.write returned $n")
                    off += n
                    written += n
                    fireCues(t)
                }
            }
            if (!cancelled && started) {
                // Let the tail play out: the head catches up with what was written, or we stop waiting.
                val deadline = SystemClock.uptimeMillis() + written / BYTES_PER_MS + 2000
                while (!cancelled && played(t) < written && SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(20)
                    fireCues(t)
                }
            }
        } catch (e: Exception) {
            if (!cancelled) Log.w(TAG, "playback ended early", e)
        } finally {
            // Read before the release: a released track has no position to give.
            val heard = runCatching { played(t) / BYTES_PER_MS }.getOrNull()
            val underruns = runCatching { t.underrunCount }.getOrNull()
            try {
                t.pause()
                t.flush()
                t.stop()
            } catch (_: Exception) {
            }
            t.release()
            track = null
            synchronized(cues) { cues.clear() }
            Log.d(TAG, "played $heard of ${written / BYTES_PER_MS} ms; underruns $underruns")
            main.post { onFinish(this) }
        }
    }

    /** Bytes heard so far: the head position is in frames, and wraps as a signed int. */
    private fun played(t: AudioTrack): Long = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) * BYTES_PER_FRAME

    private fun fireCues(t: AudioTrack) {
        val pos = played(t)
        while (true) {
            val due = synchronized(cues) { cues.peek()?.takeIf { it.atByte <= pos }?.also { cues.poll() } } ?: break
            Log.d(TAG, "cue at ${due.atByte / BYTES_PER_MS} ms fires: head ${pos / BYTES_PER_MS} ms, ${SystemClock.uptimeMillis() - startedAt} ms after start")
            main.post(due.action)
        }
    }

    private fun open(): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state == AudioTrack.STATE_INITIALIZED) {
            t
        } else {
            Log.e(TAG, "AudioTrack failed to initialise")
            t.release()
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "no audio output", e)
        null
    }

    companion object {
        private const val TAG = "AudioOut"
        const val SAMPLE_RATE = 24000
        const val BYTES_PER_FRAME = 2L
        const val BYTES_PER_MS = SAMPLE_RATE * BYTES_PER_FRAME / 1000
        /** A second of buffer: write() blocks once it is full, which paces the thread. */
        private const val BUFFER_BYTES = SAMPLE_RATE * 2
        /** 20 ms. */
        private const val SLICE_BYTES = 960
        private val END = ByteArray(0)
    }
}
