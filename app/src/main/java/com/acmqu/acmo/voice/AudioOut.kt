package com.acmqu.acmo.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
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
 * [onLevel] fires (main thread) as the sound heard crosses between silence, quiet and
 * loud -- a [MouthGate] over the bytes as they are queued, cued like everything else --
 * so the mouth moves with the voice, not with the stream.
 */
class AudioOut(
    private val onStart: (AudioOut) -> Unit,
    private val onFinish: (AudioOut) -> Unit,
    private val onLevel: (AudioOut, Int) -> Unit = { _, _ -> },
) {
    private class Cue(val atByte: Long, val action: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val cues = PriorityQueue<Cue>(compareBy { it.atByte })   // guarded by itself
    @Volatile private var track: AudioTrack? = null
    @Volatile private var cancelled = false
    private var startedAt = 0L   // uptime when the track started; the audio thread's
    private var half: Byte? = null   // an odd byte carried into the next chunk; one producer thread at a time
    private val gate = MouthGate()   // the producer thread's too
    private val effect = VoiceEffect()   // the producer thread's too

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
        if (whole == 0) return
        // Registered before the bytes are queued, so the cue is in place before they can play.
        // The mouth is gated on the DRY signal: MouthGate's thresholds are set to ElevenLabs' own
        // levels, and the effect below would skew them. The effect moves no onsets or gaps, so the
        // cues still land where the words do.
        for (c in gate.feed(data, 0, whole)) cue(c.atByte) { onLevel(this, c.level) }
        // ACMO's robotic voice, ring-modulated in place. Per-sample, so it changes no byte offset the
        // cues above depend on. The pitch is lowered separately, on the track itself, in open().
        effect.process(data, 0, whole)
        queue.put(if (whole == data.size) data else data.copyOf(whole))
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
        // Stops the sound at once, rather than when the buffer runs dry.
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
                    // Never block in write(): on the tablet a blocked write returns in half-second steps, and a cue
                    // is only checked between writes. With the buffer full, sleep 20 ms and look at the head instead.
                    val n = t.write(chunk, off, minOf(SLICE_BYTES, chunk.size - off), AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioTrack.write returned $n")
                    if (n == 0) {
                        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) throw IllegalStateException("AudioTrack stopped")
                        Thread.sleep(20)
                    }
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
            val g = gate.summary()
            Log.d(TAG, "played $heard of ${written / BYTES_PER_MS} ms; underruns $underruns; mouth open ${g.openWindows} of ${g.windows} windows, ${g.changes} changes, ${g.db}")
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
            // Shift the pitch (PITCH > 1 raises it). speed stays 1.0, so a second of audio still plays in a second --
            // that is what keeps playbackHeadPosition, and every face cue keyed to it, in step. Change
            // speed here and those offsets would need scaling. A device that rejects it just plays flat.
            runCatching { t.playbackParams = PlaybackParams().setPitch(PITCH).setSpeed(1.0f) }
                .onFailure { Log.w(TAG, "pitch shift unavailable; playing at normal pitch", it) }
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
        /** ACMO's voice, tuned here. PITCH < 1 lowers it (tempo unchanged); the ring modulator adds
         * the robotic buzz -- ROBOT_CARRIER_HZ is its pitch, ROBOT_MIX how strong (0 dry, 1 full).
         * PITCH > 1 raises the voice, < 1 lowers it. */
        const val PITCH = 1.25f
        const val ROBOT_CARRIER_HZ = 55.0
        const val ROBOT_MIX = 0.35
        const val BYTES_PER_MS = SAMPLE_RATE * BYTES_PER_FRAME / 1000
        /** A second of buffer: once it is full the thread waits for the head, 20 ms at a time. */
        private const val BUFFER_BYTES = SAMPLE_RATE * 2
        /** 20 ms. */
        private const val SLICE_BYTES = 960
        private val END = ByteArray(0)
    }
}
