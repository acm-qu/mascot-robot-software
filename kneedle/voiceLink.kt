package com.acmqu.acmo.kneedle

import android.util.Log
import com.acmqu.acmo.gemini.GeminiClient
import com.acmqu.acmo.voice.MicPipeline
import com.acmqu.acmo.voice.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The road from the microphone to the board -- one spoken sentence in, one
 * letter down the serial port out:
 *
 *   CAPTURE --wav--> transcribe --text--> clean --> runCommand --> Arduino
 *
 * Every leg of it is somebody else's code. [MicPipeline] in CAPTURE mode
 * records until the speaker stops, [GeminiClient.transcribe] turns that
 * recording into words, [clean] strips what kneedle should not see, and
 * `runCommand` in script.kt does the rest: needle, tools.json, serial.
 *
 * The wake word is assumed already said -- this class only ever asks for a
 * capture. Two ways to wire it up:
 *
 *   // 1. VoiceLink owns the microphone: wake word, command, back to waiting.
 *   val link = VoiceLink(gemini, lifecycleScope)
 *   link.attach(MicPipeline(model, link).also { it.start() })
 *   link.mic?.setMode(MicPipeline.Mode.WAKE)
 *
 *   // 2. Somebody else owns it (Brain) and hands the recording over.
 *   val link = VoiceLink(gemini, lifecycleScope)
 *   override fun onPrompt(wav: ByteArray) { link.submit(wav) }
 *
 * One run at a time, and `close()` when the app is done with the port.
 */
class VoiceLink(
    private val gemini: GeminiClient,
    private val scope: CoroutineScope,
) : MicPipeline.Listener {

    /** How a run ended. [Sent] is the only one where the board was told anything. */
    sealed class Outcome {
        /** Nothing was said, or the transcription held no words worth sending. */
        data object Silence : Outcome()

        /** A letter reached the board for [transcript]. */
        data class Sent(val transcript: String) : Outcome()

        /** [transcript] was heard, but kneedle sent nothing: no tool matched, or the port is shut. */
        data class NotSent(val transcript: String) : Outcome()

        /** The microphone or the transcription gave up. */
        data class Failed(val reason: String) : Outcome()
    }

    /** Fires at the end of every run started by [listen], [submit] or the wake word. */
    var onOutcome: ((Outcome) -> Unit)? = null

    /** The microphone this link captures from; null until [attach]. */
    var mic: MicPipeline? = null
        private set

    // The capture a run is waiting on: the wav, or null when nothing was heard.
    private val recording = AtomicReference<CompletableDeferred<ByteArray?>?>(null)
    private val busy = AtomicBoolean(false)

    /** The microphone to capture from -- its own, or the one the rest of the app already runs. */
    fun attach(mic: MicPipeline) {
        this.mic = mic
    }

    // ---- the doors in ----

    /** Records a command and runs it. The outcome goes to [onOutcome]. */
    fun listen(): Job = scope.launch { onOutcome?.invoke(listenAndRun()) }

    /** Runs a command from a recording somebody else captured. */
    fun submit(wav: ByteArray): Job = scope.launch { onOutcome?.invoke(runRecording(wav)) }

    /** Records, transcribes and runs one command, suspending until the board has been told. */
    suspend fun listenAndRun(): Outcome = once {
        val wav = record() ?: return@once Outcome.Silence
        command(clean(gemini.transcribe(wav)))
    }

    /** The same, starting from a recording -- the capture already happened. */
    suspend fun runRecording(wav: ByteArray): Outcome = once {
        command(clean(gemini.transcribe(wav)))
    }

    /** The same, starting from words -- a typed prompt, or a transcript from elsewhere. */
    suspend fun runText(text: String): Outcome = once {
        command(clean(text))
    }

    // ---- the pipeline ----

    /**
     * Flips the microphone to CAPTURE and waits out the sentence. Where it ends
     * is MicPipeline's call: [MicPipeline.END_SILENCE_MS] of quiet once speech
     * has begun, or [MicPipeline.MAX_PROMPT_MS] whatever happens. Null when the
     * speaker never started -- [MicPipeline.NO_SPEECH_MS] of nothing.
     *
     * Speech is measured against the ambient level the pipeline tracked while it
     * waited for the wake word, which is another reason this belongs after one.
     */
    private suspend fun record(): ByteArray? {
        val mic = mic ?: error("no microphone attached -- call attach() first")
        val waiting = CompletableDeferred<ByteArray?>()
        recording.set(waiting)
        mic.setMode(MicPipeline.Mode.CAPTURE)
        // The pipeline always answers one way or the other, so the timeout is
        // only here for a mic thread that has died without saying so.
        return withTimeout(CAPTURE_TIMEOUT_MS) { waiting.await() }
    }

    /**
     * The cleaned sentence, to kneedle. Off the main thread: needle is a
     * process to spawn and the serial port is a file to write.
     */
    private suspend fun command(transcript: String): Outcome {
        if (transcript.isEmpty()) {
            Log.i(TAG, "nothing usable was said")
            return Outcome.Silence
        }
        Log.i(TAG, "command: \"$transcript\"")
        val sent = withContext(Dispatchers.IO) { runCommand(transcript) }
        return if (sent) Outcome.Sent(transcript) else Outcome.NotSent(transcript)
    }

    /**
     * One run at a time. The port is held open and is not thread safe, and two
     * captures would fight over the microphone; a second caller is turned away
     * rather than queued, because a command that arrives late is worse than one
     * that never arrives. Nothing in here throws at a caller -- every ending is
     * an [Outcome], the way runCommand reports every ending as a boolean.
     */
    private suspend fun once(body: suspend () -> Outcome): Outcome {
        if (!busy.compareAndSet(false, true)) return Outcome.Failed("a command is already running")
        return try {
            body()
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "the microphone never came back")
            Outcome.Failed("the microphone never came back")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "voice command failed", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            recording.set(null)
            busy.set(false)
        }
    }

    // ---- MicPipeline.Listener (main thread) ----

    /**
     * The wake word. MicPipeline has already flipped itself to CAPTURE, so the
     * run started here steps straight into a capture that is under way.
     */
    override fun onWake() {
        if (busy.get()) return
        Log.d(TAG, "wake word -- listening for a command")
        ownRun { listenAndRun() }
    }

    override fun onPrompt(wav: ByteArray) {
        val waiting = recording.getAndSet(null)
        if (waiting != null && waiting.complete(wav)) return
        // Nobody was waiting on it: the capture was started by the wake word,
        // or by whoever owns the microphone. Run it rather than drop it.
        if (busy.get()) {
            Log.w(TAG, "a recording arrived mid-command; dropped")
            return
        }
        ownRun { runRecording(wav) }
    }

    override fun onNothingHeard() {
        val waiting = recording.getAndSet(null)
        if (waiting == null || !waiting.complete(null)) Log.d(TAG, "nothing heard")
    }

    override fun onMicError(message: String) {
        Log.e(TAG, "microphone: $message")
        recording.getAndSet(null)?.completeExceptionally(IllegalStateException(message))
    }

    /**
     * A run this class started off a callback: the microphone is its own for
     * the moment, so it goes back to the wake word at the end. A run a caller
     * asked for leaves the mode alone -- that caller decides what happens next.
     */
    private fun ownRun(body: suspend () -> Outcome) {
        scope.launch {
            val outcome = body()
            mic?.setMode(MicPipeline.Mode.WAKE)
            onOutcome?.invoke(outcome)
        }
    }

    companion object {
        private const val TAG = "VoiceLink"

        /** A capture cannot outlast this. Only a dead mic thread ever reaches it. */
        const val CAPTURE_TIMEOUT_MS = MicPipeline.MAX_PROMPT_MS + 3000L

        private val WHITESPACE = Regex("""\s+""")

        /** "[BLANK_AUDIO]", "(silence)" -- a transcriber's note that it heard no words. */
        private val NO_WORDS = Regex("""^[\[(<][^\[(<]*[\])>]$""")

        /**
         * A leading "hey ACMO," however it came out. The pre-roll means the wake
         * word itself is usually at the front of the recording, and needle should
         * match on the command, not on the robot's name. Built from the same
         * sound-alikes the recogniser listens for, so one list tunes both.
         */
        private val WAKE_PREFIX = Regex(
            "^(?:hey|hi|ok|okay|yo)?\\s*(?:acmo|acm|" +
                WakeWord.VARIANTS.joinToString("|") { it.replace(" ", "\\s*") } +
                ")\\b[\\s,.:;!?-]*",
            RegexOption.IGNORE_CASE,
        )

        /** Stripped one at a time off the front: "um, so please dance" is "dance". */
        private val FILLER = Regex(
            """^(?:u+m+|u+h+|e+r+|a+h+|h+m+|like|so|well|now|please)\b[\s,.]*""",
            RegexOption.IGNORE_CASE,
        )

        private val POLITE_SUFFIX = Regex("""\s*\b(?:please|thanks|thank you)\b[\s.!?]*$""", RegexOption.IGNORE_CASE)
        private val PUNCTUATION_SUFFIX = Regex("""[\s.!?,;:]+$""")

        /**
         * The transcript as kneedle should see it: one line, no wake word, no
         * filler, no trailing punctuation. Empty when nothing is left, which the
         * pipeline reads as silence.
         *
         * Everything here is a trim, never a rewrite -- needle matches tools on
         * the words a user actually said, so the words that survive are theirs.
         */
        fun clean(raw: String): String {
            var text = raw.trim().replace(WHITESPACE, " ")
            if (NO_WORDS.matches(text)) return ""
            text = text.trim('"', '\'', '“', '”', '‘', '’', ' ')
            text = WAKE_PREFIX.replace(text, "")
            while (true) {
                val shorter = FILLER.replace(text, "")
                if (shorter == text) break
                text = shorter
            }
            text = POLITE_SUFFIX.replace(text, "")
            return PUNCTUATION_SUFFIX.replace(text, "").trim()
        }
    }
}
