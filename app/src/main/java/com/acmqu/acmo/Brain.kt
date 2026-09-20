package com.acmqu.acmo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.face.FaceView
import com.acmqu.acmo.gemini.LiveSession
import com.acmqu.acmo.gemini.Personality
import com.acmqu.acmo.gemini.Sentence
import com.acmqu.acmo.remote.Entry
import com.acmqu.acmo.remote.FaceCues
import com.acmqu.acmo.remote.Failure
import com.acmqu.acmo.remote.Line
import com.acmqu.acmo.remote.RemoteServer
import com.acmqu.acmo.remote.Said
import com.acmqu.acmo.remote.Snapshot
import com.acmqu.acmo.remote.Tags
import com.acmqu.acmo.voice.AudioOut
import com.acmqu.acmo.voice.ElevenLabs
import com.acmqu.acmo.voice.MicPipeline
import com.acmqu.acmo.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
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
 *
 * The remote console is the other way in: lines an operator typed, spoken in
 * an ElevenLabs voice ([say]). They queue up and play one after another, or
 * cut in. A line is SPEAKING like a reply is, with the face the operator chose.
 */
class Brain(
    private val face: FaceView,
    /** Android's own text-to-speech: only for the things said when the session cannot. */
    private val speaker: Speaker,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val apology: String,
    /** The console's voice; null when no ElevenLabs key was built in. */
    private val eleven: ElevenLabs?,
) : MicPipeline.Listener, RemoteServer.Host {

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

    // Lines from the console: the one playing, and the ones waiting their turn.
    private val queue = ArrayDeque<Entry>()
    @Volatile private var playing: Entry? = null   // read on the socket thread too
    private var call: Call? = null                 // the ElevenLabs stream of [playing]
    private var nextId = 1
    private var lastFailure: Failure? = null
    private val main = Handler(Looper.getMainLooper())

    /** Whether ACMO listens for its name and talks through Gemini at all; see [setConversation]. */
    private var conversation = true

    /**
     * A Gemini exchange in flight: listening, thinking, or its voice playing. A console
     * line's voice is not it, and neither is the quiet after a failed one.
     */
    private val conversing: Boolean
        get() = state == State.LISTENING || state == State.THINKING ||
            (state == State.SPEAKING && out != null && playing == null)

    fun start(model: Model) {
        mic = MicPipeline(model, this).also { it.start() }
        // A typed prompt or a remote line may still be in flight, or have come and gone, while
        // the model loaded (BOOTING allows both): the microphone joins whatever state that left.
        when (state) {
            State.BOOTING -> goIdle()
            State.IDLE -> mic?.setMode(idleMode())
            else -> mic?.setMode(MicPipeline.Mode.PAUSED)
        }
    }

    /**
     * Listening for "hey ACMO" and talking through Gemini can be switched off -- for a scripted
     * show, when only the console should make ACMO talk. Off, the microphone stays paused, typed
     * prompts are ignored, and a conversation in progress is cut short.
     */
    fun setConversation(on: Boolean) {
        if (conversation == on) return
        conversation = on
        Log.i(TAG, if (on) "conversation on" else "conversation off")
        when {
            on -> if (state == State.IDLE) mic?.setMode(MicPipeline.Mode.WAKE)
            conversing -> {
                interrupt()
                goIdle()
            }
            state == State.IDLE -> mic?.setMode(MicPipeline.Mode.PAUSED)
        }
    }

    /** What the microphone does while ACMO waits: listens for its name, or nothing. */
    private fun idleMode() = if (conversation) MicPipeline.Mode.WAKE else MicPipeline.Mode.PAUSED

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
        dropLines()
        closeSession()
        speaker.stop()
        state = State.BOOTING
    }

    /** The app left or returned to the foreground. Background microphones are silent on modern Android anyway. */
    fun setForeground(foreground: Boolean) {
        if (mic == null) return
        if (foreground) {
            if (state == State.IDLE) mic?.setMode(idleMode())
        } else {
            val o = out
            out = null
            o?.cancel()
            dropLines()
            speaker.stop()
            goIdle()
            mic?.setMode(MicPipeline.Mode.PAUSED)
            closeSession()
        }
    }

    /** Dev mode: listen right now, no wake word needed -- or, if already listening, stop. */
    fun toggleListening() {
        if (mic == null || !conversation) return   // no microphone yet (permission, model), or nothing to listen for
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
        if (prompt.isEmpty() || !conversation) return
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
        if (state != State.IDLE || !conversation) {
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
            State.IDLE -> mic?.setMode(idleMode())
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
            if (playing != null) return   // a console line has the player; nothing the session sends now is for it
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
            if (playing == null) out?.finish()   // a console line's player is not the model's to end
        }

        override fun onInterrupted() {
            if (!current()) return
            Log.i(TAG, "interrupted")
            if (playing == null) out?.cancel()
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
            if (conversing) fail("session lost")   // a console line playing over an idle session is not a lost reply
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
        playing?.let { Log.i(TAG, "line #${it.id} playing") }
        face.setSpeaking(true)
        // A reply has a fixed limit; a line from the console gets one that fits its length.
        val limit = playing?.let { LINE_GRACE_MS + LINE_MS_PER_CHAR * it.line.text.length } ?: MAX_SPEAK_MS
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(limit)
            if (state == State.SPEAKING && out === o) {
                Log.w(TAG, "${playing?.let { "line #${it.id}" } ?: "the reply"} ran long; cutting it off")
                o.cancel()
            }
        }
    }

    private fun onSpeechEnd(o: AudioOut) {
        if (o !== out) return
        out = null
        face.setSpeaking(false)
        if (state != State.SPEAKING) return
        if (playing != null) {
            lineEnded()
        } else {
            lastExchangeAt = SystemClock.elapsedRealtime()
            goIdle()
        }
    }

    // ---- lines from the console (RemoteServer.Host, main thread) ----

    override fun say(line: Line, now: Boolean): Said {
        val entry = Entry(nextId++, line)
        if (now) {
            interrupt()
            queue.addFirst(entry)
            playNext()
            return Said(entry.id, 0)
        }
        val ahead = queue.size + (if (playing != null) 1 else 0)
        queue.addLast(entry)
        if (state == State.IDLE || state == State.BOOTING) playNext()
        return Said(entry.id, ahead)
    }

    /** Be quiet: whatever is playing stops, the queue is forgotten, and ACMO waits for its name. */
    override fun hush() {
        Log.i(TAG, "hush")
        interrupt()
        queue.clear()
        idle()
    }

    override fun snapshot(): Snapshot = Snapshot(state, face.expression, playing, queue.toList(), lastFailure)

    /** Cancels the remote line playing and forgets the ones waiting. */
    private fun dropLines() {
        call?.cancel()
        call = null
        queue.clear()
        playing = null
    }

    /** Cuts off whatever ACMO is doing -- a remote line, or a conversation -- without deciding what comes next. */
    private fun interrupt() {
        val wasConversing = conversing   // read before the player and the jobs are gone
        listenJob?.cancel()
        replyJob?.cancel()
        previewJob?.cancel()
        mic?.sink = null
        mic?.setMode(MicPipeline.Mode.PAUSED)
        val o = out
        out = null
        o?.cancel()
        call?.cancel()
        call = null
        speaker.stop()
        faces = emptyList()
        faceIndex = 0
        face.setSpeaking(false)
        if (wasConversing) {
            // Dropping the socket is what keeps the model's late audio out of the player. The
            // resumption handle survives, so the next "hey ACMO" still remembers the conversation.
            Log.i(TAG, "interrupting the conversation")
            closeSession()
            heard.clear()
            said.clear()
        }
        playing = null
    }

    /** Speaks the next line from the console, or goes idle when there is none. */
    private fun playNext() {
        val entry = queue.removeFirstOrNull() ?: run {
            idle()
            return
        }
        val voice = eleven ?: run {
            // Unreachable from the server, which answers 503 without a key.
            lastFailure = Failure(entry.id, "no ElevenLabs key")
            idle()
            return
        }
        listenJob?.cancel()
        replyJob?.cancel()
        previewJob?.cancel()
        mic?.sink = null
        mic?.setMode(MicPipeline.Mode.PAUSED)
        out?.let {   // a reply that slipped in late; it would swallow this line's audio
            out = null
            it.cancel()
        }
        playing = entry
        faces = emptyList()
        faceIndex = 0
        lastFailure = null
        state = State.SPEAKING
        // The operator's face until the first face tag -- or that tag's face, if only tags come before it:
        // the v3 model takes a second or two to start, and the face should not flip when it does.
        val text = entry.line.text
        val tags = Tags.faces(text)
        val opening = Tags.opening(text) ?: entry.line.feeling
        val expressive = Tags.hasTags(text)
        face.setExpression(opening)
        Log.i(TAG, "line #${entry.id} (${opening.label}${if (expressive) ", expressive" else ""}): \"$text\"")
        // The player is made here, on the main thread, so the socket thread only ever feeds this one.
        val o = player()
        val cues = FaceCues(tags)
        call = voice.stream(text, expressive = expressive, sink = object : ElevenLabs.Sink {
            override fun timed(atByte: LongArray) {
                // OkHttp's thread. A cue is registered before the audio it points into reaches the player,
                // and guarded by identity: a stopped line's cue must never touch the next line's face.
                for (c in cues.feed(atByte)) {
                    Log.i(TAG, "line #${entry.id}: cue ${c.feeling.label} at ${c.atByte / AudioOut.BYTES_PER_MS} ms")
                    o.cue(c.atByte) { if (playing === entry) face.setExpression(c.feeling) }
                }
            }

            override fun play(pcm: ByteArray) = o.play(pcm)

            override fun finish() {
                // Still OkHttp's thread, like feed(): the count is only ever touched there.
                if (cues.pending > 0) Log.w(TAG, "line #${entry.id}: ${cues.pending} face tag(s) never reached")
                o.finish()
            }

            override fun fail(message: String) {
                main.post { lineFailed(entry, o, message) }
            }
        })
    }

    /** The last byte of the line has been heard. */
    private fun lineEnded() {
        val entry = playing ?: return
        Log.i(TAG, "line #${entry.id} done")
        playing = null
        call?.cancel()   // a stream still running into a cancelled player (the watchdog, a dead AudioTrack)
        call = null
        playNext()
    }

    /** ElevenLabs could not deliver the line: a sad face for a moment, then on with the queue. */
    private fun lineFailed(entry: Entry, o: AudioOut, message: String) {
        if (entry !== playing) return
        Log.e(TAG, "line #${entry.id} failed: $message")
        lastFailure = Failure(entry.id, message)
        if (out === o) out = null
        o.cancel()
        call = null
        playing = null
        face.setSpeaking(false)
        face.setExpression(Expression.SAD)
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(FAIL_PAUSE_MS)
            playNext()
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
        // Kept in replyJob so a line cutting in can stop the apology from going idle underneath it.
        replyJob = scope.launch {
            try {
                speaker.speak(apology, "en")
            } catch (_: Exception) {
            }
            if (isActive) goIdle()
        }
    }

    /** Back to waiting for the wake word -- unless the console has lines waiting, which come first. */
    private fun goIdle() {
        if (queue.isNotEmpty()) playNext() else idle()
    }

    private fun idle() {
        listenJob?.cancel()
        replyJob?.cancel()
        state = State.IDLE
        faces = emptyList()
        faceIndex = 0
        playing = null
        face.setSpeaking(false)
        face.setExpression(Expression.IDLE)
        mic?.sink = null
        mic?.setMode(idleMode())
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
         * A line from the console may play this long plus [LINE_MS_PER_CHAR] for each of its
         * characters. Speech is 70-100 ms a character; the budget is generous because it only
         * has to catch a stuck player -- a stalled stream is already ended by ElevenLabs' read
         * timeout. The longest line (2 000 characters) gets 310 s.
         */
        const val LINE_GRACE_MS = 10_000L
        const val LINE_MS_PER_CHAR = 150L

        /** How long the sad face stays after a remote line fails, before the next one. */
        const val FAIL_PAUSE_MS = 1500L

        /**
         * The transcript runs about a second ahead of the audio it describes,
         * so a sentence that ends in a transcript chunk ends in the audio about
         * this much later. Tune it if the face changes early or late.
         */
        const val CUE_LEAD_BYTES = 1000 * AudioOut.BYTES_PER_MS
    }
}
