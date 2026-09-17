package com.acmqu.acmo

import android.os.SystemClock
import android.util.Log
import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.face.FaceView
import com.acmqu.acmo.gemini.GeminiClient
import com.acmqu.acmo.voice.MicPipeline
import com.acmqu.acmo.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vosk.Model

/**
 * What ACMO does, as one state machine on the main thread:
 *
 *   IDLE ──wake word──▶ LISTENING ──prompt──▶ THINKING ──reply──▶ SPEAKING ──▶ IDLE
 *
 * The face is idle while waiting, excited from the wake word until the reply
 * arrives, then shows each segment's feeling while it is spoken. Errors are
 * a sad face and a spoken apology, because the face is the only screen there is.
 */
class Brain(
    private val face: FaceView,
    private val speaker: Speaker,
    private val gemini: GeminiClient,
    private val scope: CoroutineScope,
    private val apology: String,
) : MicPipeline.Listener {

    enum class State { BOOTING, IDLE, LISTENING, THINKING, SPEAKING }

    var state = State.BOOTING
        private set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    /** Fires on the main thread whenever [state] changes; the dev bar's mic button watches it. */
    var onStateChanged: ((State) -> Unit)? = null

    private var mic: MicPipeline? = null
    private var job: Job? = null
    private var previewJob: Job? = null

    // Conversation memory: the last interaction id, forgotten after a quiet spell.
    private var lastInteractionId: String? = null
    private var lastExchangeAt = 0L

    fun start(model: Model) {
        mic = MicPipeline(model, this).also { it.start() }
        goIdle()
    }

    fun stop() {
        job?.cancel()
        previewJob?.cancel()
        mic?.stop()
        mic = null
        speaker.stop()
        state = State.BOOTING
    }

    /** The app left or returned to the foreground. Background microphones are silent on modern Android anyway. */
    fun setForeground(foreground: Boolean) {
        if (mic == null) return
        if (foreground) {
            if (state == State.IDLE) mic?.setMode(MicPipeline.Mode.WAKE)
        } else {
            job?.cancel()
            speaker.stop()
            goIdle()
            mic?.setMode(MicPipeline.Mode.PAUSED)
        }
    }

    /**
     * Dev mode: start listening for a prompt right now, no wake word needed --
     * or, if already listening, send what has been said so far.
     */
    fun toggleListening() {
        if (mic == null) return   // no microphone yet (permission, model): nothing could end a capture
        when (state) {
            State.IDLE -> {
                previewJob?.cancel()
                state = State.LISTENING
                face.setExpression(Expression.EXCITED)
                mic?.setMode(MicPipeline.Mode.CAPTURE)
            }
            State.LISTENING -> mic?.finishCapture()
            else -> {}
        }
    }

    /** Dev mode: a typed prompt, straight to the model with no transcription. */
    fun submitText(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        // BOOTING is allowed on purpose: a typed prompt is the one way to talk to
        // ACMO on a device with no microphone, or before the model has loaded.
        if (state != State.IDLE && state != State.LISTENING && state != State.BOOTING) return
        job?.cancel()
        previewJob?.cancel()
        mic?.setMode(MicPipeline.Mode.PAUSED)
        state = State.THINKING
        face.setExpression(Expression.EXCITED)
        job = scope.launch { converse { prompt } }
    }

    /** A tap on the idle face shows the next expression for a few seconds -- a preview, nothing more. */
    fun previewNext() {
        if (state != State.IDLE) return
        previewJob?.cancel()
        face.setExpression(face.expression.next())
        previewJob = scope.launch {
            delay(4000)
            if (state == State.IDLE) face.setExpression(Expression.IDLE)
        }
    }

    // ---- MicPipeline.Listener (main thread) ----

    override fun onWake() {
        if (state != State.IDLE) {
            // The mic flipped itself to CAPTURE; put it back where this state wants it.
            mic?.setMode(if (state == State.IDLE) MicPipeline.Mode.WAKE else MicPipeline.Mode.PAUSED)
            return
        }
        previewJob?.cancel()
        state = State.LISTENING
        face.setExpression(Expression.EXCITED)
    }

    override fun onNothingHeard() {
        if (state != State.LISTENING) {
            if (state == State.IDLE) mic?.setMode(MicPipeline.Mode.WAKE)
            return
        }
        job = scope.launch { shrug() }
    }

    override fun onPrompt(wav: ByteArray) {
        if (state != State.LISTENING) {
            if (state == State.IDLE) mic?.setMode(MicPipeline.Mode.WAKE)
            return
        }
        state = State.THINKING
        job = scope.launch { converse { gemini.transcribe(wav) } }
    }

    override fun onMicError(message: String) {
        Log.e(TAG, "microphone: $message")
        face.setExpression(Expression.SAD)
    }

    // ---- the conversation ----

    /** [hear] yields the prompt text: a transcription of the recording, or what was typed. */
    private suspend fun converse(hear: suspend () -> String) {
        try {
            val transcript = hear()
            Log.i(TAG, "heard: \"$transcript\"")
            if (transcript.isBlank()) {
                shrug()
                return
            }

            val now = SystemClock.elapsedRealtime()
            val previous = lastInteractionId?.takeIf { now - lastExchangeAt < MEMORY_MS }
            val reply = gemini.reply(transcript, previous)
            lastInteractionId = reply.interactionId
            lastExchangeAt = SystemClock.elapsedRealtime()

            state = State.SPEAKING
            for (segment in reply.segments) {
                Log.i(TAG, "[${segment.feeling.label}] ${segment.text}")
                face.setExpression(segment.feeling)
                speaker.speak(segment.text, reply.language)
            }
            goIdle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "conversation failed", e)
            face.setExpression(Expression.SAD)
            try {
                speaker.speak(apology, "en")
            } catch (_: Exception) {
            }
            goIdle()
        }
    }

    /** Nothing usable was heard: a surprised look, then back to waiting. */
    private suspend fun shrug() {
        face.setExpression(Expression.SURPRISED)
        delay(1500)
        goIdle()
    }

    private fun goIdle() {
        state = State.IDLE
        face.setSpeaking(false)
        face.setExpression(Expression.IDLE)
        mic?.setMode(MicPipeline.Mode.WAKE)
    }

    companion object {
        private const val TAG = "Brain"
        /** How long ACMO keeps the thread of a conversation after the last exchange. */
        const val MEMORY_MS = 10 * 60 * 1000L
    }
}
