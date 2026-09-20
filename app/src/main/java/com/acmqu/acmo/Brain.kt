package com.acmqu.acmo

import android.os.SystemClock
import android.util.Log
import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.face.FaceView
import com.acmqu.acmo.gemini.LiveSession
import com.acmqu.acmo.gemini.Personality
import com.acmqu.acmo.gemini.Sentence
import com.acmqu.acmo.voice.AudioOut
import com.acmqu.acmo.voice.MicPipeline
import com.acmqu.acmo.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vosk.Model

/**
 * What ACMO does, as one state machine on the main thread:
 *
 *   IDLE ──wake word──▶ LISTENING ──the model answers──▶ THINKING ──its voice──▶ SPEAKING ──▶ IDLE
 *
 * The wake word is heard on the tablet; from then on the microphone streams
 * into a Gemini Live session, which decides when the person has finished,
 * plans the faces for its answer (one per sentence, through a tool call) and
 * speaks it. The face is idle while waiting, excited from the wake word until
 * the answer begins, then walks the planned faces as the sentences are heard.
 *
 * One session is kept open across exchanges so that follow-ups remember the
 * conversation; it is dropped after [MEMORY_MS] of quiet. Errors are a sad
 * face and a spoken apology, because the face is the only screen there is.
 */
class Brain(
    private val face: FaceView,
    /** Android's own text-to-speech: only for the things said when the session cannot. */
    private val speaker: Speaker,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val apology: String,
) : MicPipeline.Listener {

    enum class State { BOOTING, IDLE, LISTENING, THINKING, SPEAKING }

    @Volatile var state = State.BOOTING
        private set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    /** Fires on the main thread whenever [state] changes; the dev bar's mic button watches it. */
    var onStateChanged: ((State) -> Unit)? = null

    @Volatile private var mic: MicPipeline? = null
    @Volatile private var session: LiveSession? = null
    private var sessionExpiring = false
    private var resumptionHandle: String? = null
    private var lastExchangeAt = 0L

    private var listenJob: Job? = null    // gives up on a wake that leads nowhere
    private var replyJob: Job? = null     // gives up on a model that never answers, or never stops
    private var forgetJob: Job? = null    // closes a quiet session after MEMORY_MS
    private var previewJob: Job? = null

    // The reply being spoken. Whichever thread sees the reply first creates the player.
    @Volatile private var out: AudioOut? = null
    private var faces: List<Expression> = emptyList()
    private var faceIndex = 0
    private val heard = StringBuilder()
    private val said = StringBuilder()

    fun start(model: Model) {
        mic = MicPipeline(model, this).also { it.start() }
        // A typed prompt may already be in flight (BOOTING allows it): then the
        // microphone just waits its turn instead of resetting the conversation.
        if (state == State.BOOTING) goIdle() else mic?.setMode(MicPipeline.Mode.PAUSED)
    }

    fun stop() {
        listenJob?.cancel()
        replyJob?.cancel()
        forgetJob?.cancel()
        previewJob?.cancel()
        mic?.stop()
        mic = null
        val o = out
        out = null
        o?.cancel()
        closeSession()
        speaker.stop()
        state = State.BOOTING
    }

    /** The app left or returned to the foreground. Background microphones are silent on modern Android anyway. */
    fun setForeground(foreground: Boolean) {
        if (mic == null) return
        if (foreground) {
            if (state == State.IDLE) mic?.setMode(MicPipeline.Mode.WAKE)
        } else {
            val o = out
            out = null
            o?.cancel()
            speaker.stop()
            goIdle()
            mic?.setMode(MicPipeline.Mode.PAUSED)
            closeSession()
        }
    }

    /** Dev mode: listen right now, no wake word needed -- or, if already listening, stop. */
    fun toggleListening() {
        if (mic == null) return   // no microphone yet (permission, model)
        when (state) {
            State.IDLE -> listen()
            State.LISTENING -> {
                mic?.setMode(MicPipeline.Mode.PAUSED)
                goIdle()
            }
            else -> {}
        }
    }

    /** Dev mode: a typed prompt, straight to the model. */
    fun submitText(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        // BOOTING is allowed on purpose: a typed prompt is the one way to talk to
        // ACMO on a device with no microphone, or before the model has loaded.
        if (state != State.IDLE && state != State.LISTENING && state != State.BOOTING) return
        listenJob?.cancel()
        previewJob?.cancel()
        mic?.setMode(MicPipeline.Mode.PAUSED)
        state = State.THINKING
        face.setExpression(Expression.EXCITED)
        Log.i(TAG, "typed: \"$prompt\"")
        openSession().sendText(prompt)
        armReplyTimeout()
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
            // The mic flipped itself to STREAM; this state does not want that.
            mic?.setMode(MicPipeline.Mode.PAUSED)
            return
        }
        listen()
    }

    override fun onSpeech() {
        Log.d(TAG, "speech started")
    }

    override fun onNothingHeard() {
        when (state) {
            State.LISTENING -> shrug()
            State.IDLE -> mic?.setMode(MicPipeline.Mode.WAKE)
            else -> {}
        }
    }

    override fun onMicError(message: String) {
        Log.e(TAG, "microphone: $message")
        face.setExpression(Expression.SAD)
    }

    // ---- listening ----

    private fun listen() {
        previewJob?.cancel()
        state = State.LISTENING
        face.setExpression(Expression.EXCITED)
        val s = openSession()
        mic?.sink = s::sendAudio
        mic?.setMode(MicPipeline.Mode.STREAM)
        listenJob?.cancel()
        listenJob = scope.launch {
            delay(MAX_LISTEN_MS)
            if (state == State.LISTENING) shrug()
        }
    }

    /** Nothing usable was heard: a surprised look, then back to waiting. */
    private fun shrug() {
        listenJob?.cancel()
        mic?.setMode(MicPipeline.Mode.PAUSED)
        face.setExpression(Expression.SURPRISED)
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(1500)
            goIdle()
        }
    }

    // ---- the session ----

    private fun openSession(): LiveSession {
        session?.let { if (!sessionExpiring) return it }
        closeSession()
        val handle = resumptionHandle?.takeIf { SystemClock.elapsedRealtime() - lastExchangeAt < MEMORY_MS }
        val listener = SessionListener()
        val s = LiveSession(apiKey, Personality.setup(handle), listener)
        listener.self = s
        session = s
        sessionExpiring = false
        Log.i(TAG, if (handle != null) "opening a session, continuing the conversation" else "opening a session")
        s.connect()
        return s
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        sessionExpiring = false
        resumptionHandle = s.resumptionHandle ?: resumptionHandle
        s.close()
    }

    /** The model has started answering: stop listening, start waiting for its voice. */
    private fun replyBegins() {
        if (state == State.LISTENING) {
            listenJob?.cancel()
            mic?.setMode(MicPipeline.Mode.PAUSED)
            state = State.THINKING
        }
        armReplyTimeout()
    }

    private fun armReplyTimeout() {
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(REPLY_TIMEOUT_MS)
            if (state == State.THINKING) fail("no answer within ${REPLY_TIMEOUT_MS / 1000} s")
        }
    }

    /** Callbacks from one session. A session that is no longer [Brain.session] is ignored. */
    private inner class SessionListener : LiveSession.Listener {
        lateinit var self: LiveSession
        private fun current() = self === session

        override fun onReady() {
            if (current()) Log.i(TAG, "session ready")
        }

        override fun onFaces(feelings: List<Expression>) {
            if (!current() || (state != State.LISTENING && state != State.THINKING)) return
            replyBegins()
            faces = feelings
            faceIndex = 0
            face.setExpression(feelings.first())
            Log.i(TAG, "faces: ${feelings.joinToString(" ") { it.label }}")
        }

        override fun onAudio(pcm: ByteArray, bytesBefore: Long) {
            // Socket thread. Anything arriving while idle is a reply to something ACMO gave up on.
            if (!current()) return
            val st = state
            if (st != State.LISTENING && st != State.THINKING && st != State.SPEAKING) return
            mic?.setMode(MicPipeline.Mode.PAUSED)   // the robot must not hear itself
            player().play(pcm)
        }

        override fun onTranscript(text: String, input: Boolean, audioBytes: Long) {
            if (!current()) return
            if (input) {
                heard.append(text)
                return
            }
            said.append(text)
            if (state != State.THINKING && state != State.SPEAKING) return
            // The next sentence gets the next planned face, once the audio has got there.
            if (Sentence.endsIn(text) && faceIndex + 1 < faces.size) {
                val next = faces[++faceIndex]
                player().cue(audioBytes + CUE_LEAD_BYTES) {
                    if (state == State.SPEAKING) face.setExpression(next)
                }
            }
        }

        override fun onTurnComplete(audioBytes: Long) {
            if (!current()) return
            if (audioBytes == 0L) return   // the turn that only planned the faces; the spoken one follows
            if (heard.isNotEmpty()) Log.i(TAG, "heard: \"${heard.trim()}\"")
            if (said.isNotEmpty()) Log.i(TAG, "said: \"${said.trim()}\"")
            heard.clear()
            said.clear()
            out?.finish()
        }

        override fun onInterrupted() {
            if (!current()) return
            Log.i(TAG, "interrupted")
            out?.cancel()
        }

        override fun onGoAway(timeLeftMs: Long) {
            if (!current()) return
            Log.i(TAG, "session expiring in $timeLeftMs ms")
            sessionExpiring = true
            if (state == State.IDLE) closeSession()
        }

        override fun onClosed(failure: String?) {
            if (!current()) return
            resumptionHandle = self.resumptionHandle ?: resumptionHandle
            session = null
            if (failure == null) return
            Log.w(TAG, "session lost: $failure")
            if (self.resumed && !self.ready && state == State.LISTENING) {
                // A stale handle, most likely. Once more, from scratch.
                resumptionHandle = null
                val s = openSession()
                mic?.sink = s::sendAudio
                return
            }
            if (state == State.LISTENING || state == State.THINKING || state == State.SPEAKING) fail("session lost")
        }
    }

    // ---- the voice ----

    private fun player(): AudioOut {
        out?.let { return it }
        synchronized(this) {
            out?.let { return it }
            return AudioOut(onStart = ::onSpeechStart, onFinish = ::onSpeechEnd).also { out = it }
        }
    }

    private fun onSpeechStart(o: AudioOut) {
        if (o !== out) return
        if (state == State.LISTENING || state == State.THINKING) {
            replyBegins()
            state = State.SPEAKING
            if (faces.isEmpty()) face.setExpression(Expression.HAPPY)   // it spoke without planning faces
        }
        face.setSpeaking(true)
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(MAX_SPEAK_MS)
            if (state == State.SPEAKING && out === o) {
                Log.w(TAG, "the reply ran long; cutting it off")
                o.cancel()
            }
        }
    }

    private fun onSpeechEnd(o: AudioOut) {
        if (o !== out) return
        out = null
        face.setSpeaking(false)
        if (state == State.SPEAKING) {
            lastExchangeAt = SystemClock.elapsedRealtime()
            goIdle()
        }
    }

    // ---- failure, and back to idle ----

    private fun fail(why: String) {
        Log.e(TAG, "conversation failed: $why")
        listenJob?.cancel()
        replyJob?.cancel()
        mic?.setMode(MicPipeline.Mode.PAUSED)
        val o = out
        out = null
        o?.cancel()
        face.setSpeaking(false)
        face.setExpression(Expression.SAD)
        scope.launch {
            try {
                speaker.speak(apology, "en")
            } catch (_: Exception) {
            }
            goIdle()
        }
    }

    private fun goIdle() {
        listenJob?.cancel()
        replyJob?.cancel()
        state = State.IDLE
        faces = emptyList()
        faceIndex = 0
        face.setSpeaking(false)
        face.setExpression(Expression.IDLE)
        mic?.sink = null
        mic?.setMode(MicPipeline.Mode.WAKE)
        if (sessionExpiring) closeSession()
        forgetJob?.cancel()
        forgetJob = scope.launch {
            delay(MEMORY_MS)
            if (state == State.IDLE) {
                closeSession()
                resumptionHandle = null
                Log.i(TAG, "forgot the conversation")
            }
        }
    }

    companion object {
        private const val TAG = "Brain"

        /** How long ACMO keeps the thread of a conversation after the last exchange. */
        const val MEMORY_MS = 10 * 60 * 1000L

        /** From the wake word, how long the person may take before ACMO gives up. */
        const val MAX_LISTEN_MS = 20_000L

        /** From the model's first sign of an answer, how long its voice may take to start. */
        const val REPLY_TIMEOUT_MS = 20_000L

        /** No reply is this long; if one is, the socket has stopped saying so. */
        const val MAX_SPEAK_MS = 90_000L

        /**
         * The transcript runs about a second ahead of the audio it describes,
         * so a sentence that ends in a transcript chunk ends in the audio about
         * this much later. Tune it if the face changes early or late.
         */
        const val CUE_LEAD_BYTES = 1000 * AudioOut.BYTES_PER_MS
    }
}
