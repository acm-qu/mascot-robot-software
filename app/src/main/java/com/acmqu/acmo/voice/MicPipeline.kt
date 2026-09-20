package com.acmqu.acmo.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * One microphone thread for the whole app. It reads 16 kHz mono PCM in 100 ms
 * chunks and, depending on the mode, feeds them to the wake-word recogniser,
 * streams them to the Live session, or throws them away.
 *
 * - WAKE: chunks go to Vosk. When the grammar hears a wake variant the pipeline
 *   flips itself to STREAM -- no chunk is dropped -- and calls [Listener.onWake].
 * - STREAM: chunks go to [sink], which is the Live session; the server decides
 *   when the person has finished. Locally, if nothing that sounds like speech
 *   arrives within [NO_SPEECH_MS], [Listener.onNothingHeard] and PAUSED.
 * - PAUSED: chunks are discarded. The robot is thinking or talking, and must
 *   not hear itself.
 *
 * Quiet vs. speech is an RMS threshold a few times the ambient level, which is
 * tracked while waiting for the wake word. Callbacks arrive on the main thread.
 */
class MicPipeline(private val model: Model, private val listener: Listener) {

    interface Listener {
        fun onWake()

        /** The person started talking after the wake word. */
        fun onSpeech()

        fun onNothingHeard()
        fun onMicError(message: String)
    }

    enum class Mode { WAKE, STREAM, PAUSED }

    /** Where STREAM chunks go: 100 ms of 16 kHz mono 16-bit PCM at a time, on the mic thread. */
    @Volatile var sink: ((ByteArray) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val mode = AtomicReference(Mode.PAUSED)
    @Volatile private var running = false
    private var thread: Thread? = null

    // Everything below is touched only on the mic thread.
    private var noiseFloor = 300.0
    private var streamedMs = 0
    private var speechStarted = false
    private var loudRun = 0
    private var lastHeard = ""

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "acmo-mic").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    fun setMode(m: Mode) {
        mode.set(m)
    }

    private fun loop() {
        val record = openRecord() ?: return
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), WakeWord.GRAMMAR)
        val chunk = ShortArray(SAMPLE_RATE * CHUNK_MS / 1000)
        var lastMode: Mode? = null
        try {
            record.startRecording()
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val m = mode.get()
                if (m != lastMode) {
                    when (m) {
                        Mode.WAKE -> recognizer.reset()
                        Mode.STREAM -> resetStream()
                        Mode.PAUSED -> {}
                    }
                    lastMode = m
                }
                when (m) {
                    Mode.WAKE -> wakeStep(recognizer, chunk, n)
                    Mode.STREAM -> streamStep(chunk, n)
                    Mode.PAUSED -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "mic thread died", e)
            main.post { listener.onMicError(e.message ?: e.javaClass.simpleName) }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            recognizer.close()
        }
    }

    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            main.post { listener.onMicError("no 16 kHz mono input on this device") }
            return null
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2),   // at least a second of slack
            )
        } catch (e: Exception) {
            main.post { listener.onMicError("microphone unavailable: ${e.message}") }
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            main.post { listener.onMicError("microphone failed to initialise") }
            return null
        }
        return record
    }

    // ---- WAKE ----

    private fun wakeStep(recognizer: Recognizer, chunk: ShortArray, n: Int) {
        trackNoise(rms(chunk, n))
        val text = if (recognizer.acceptWaveForm(chunk, n)) {
            field(recognizer.result, "text")
        } else {
            field(recognizer.partialResult, "partial")
        }
        if (text.isEmpty()) return
        if (text != lastHeard) {
            lastHeard = text
            Log.d(TAG, "wake heard: $text")
        }
        if (WakeWord.matches(text)) {
            recognizer.reset()
            if (mode.compareAndSet(Mode.WAKE, Mode.STREAM)) {
                resetStream()
                main.post { listener.onWake() }
            }
        }
    }

    /** Ambient level: follows quiet quickly, loud slowly, so speech does not drag it up. */
    private fun trackNoise(r: Double) {
        noiseFloor = if (r < noiseFloor) noiseFloor * 0.8 + r * 0.2 else noiseFloor * 0.995 + r * 0.005
    }

    // ---- STREAM ----

    private fun resetStream() {
        streamedMs = 0
        speechStarted = false
        loudRun = 0
    }

    private fun streamStep(chunk: ShortArray, n: Int) {
        sink?.invoke(toBytes(chunk, n))
        streamedMs += CHUNK_MS
        if (speechStarted) return

        val threshold = (noiseFloor * THRESHOLD_GAIN).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        loudRun = if (rms(chunk, n) > threshold) loudRun + 1 else 0
        if (loudRun >= 2) {
            speechStarted = true
            main.post { listener.onSpeech() }
        } else if (streamedMs >= NO_SPEECH_MS) {
            mode.set(Mode.PAUSED)
            main.post { listener.onNothingHeard() }
        }
    }

    // ---- helpers ----

    private fun rms(chunk: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = chunk[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    private fun toBytes(chunk: ShortArray, n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(chunk, 0, n)
        return buf.array()
    }

    private fun field(json: String?, name: String): String =
        try { JSONObject(json ?: "{}").optString(name, "") } catch (_: Exception) { "" }

    companion object {
        private const val TAG = "MicPipeline"
        const val SAMPLE_RATE = 16000
        const val CHUNK_MS = 100

        /** Speech must start this soon after the wake word, or nothing was heard. */
        const val NO_SPEECH_MS = 5000

        /** Speech is this many times the ambient RMS, clamped to a sane band. */
        const val THRESHOLD_GAIN = 3.5
        const val MIN_THRESHOLD = 400.0
        const val MAX_THRESHOLD = 3000.0
    }
}
