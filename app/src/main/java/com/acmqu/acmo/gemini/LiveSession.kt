package com.acmqu.acmo.gemini

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.acmqu.acmo.face.Expression
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * One conversation with Gemini Live over a WebSocket: the microphone goes up
 * as 16 kHz PCM, the model's voice comes down as 24 kHz PCM, and in between it
 * calls [Personality.FACES_TOOL] to say which faces go with the answer.
 *
 * A session lives until [close], the server's `goAway`, or a failure. The
 * [resumptionHandle] it hands out lets the next session continue the same
 * conversation.
 *
 * Every [Listener] call arrives on the main thread except [Listener.onAudio],
 * which stays on the socket thread so playback never waits for the UI.
 */
class LiveSession(
    apiKey: String,
    private val setup: JSONObject,
    private val listener: Listener,
    private val http: OkHttpClient = defaultClient,
) {
    interface Listener {
        /** The server accepted the setup; anything sent before this has now gone out. */
        fun onReady()

        /** The model planned the faces for the answer it is about to speak. */
        fun onFaces(feelings: List<Expression>)

        /** A chunk of the model's speech. [bytesBefore] is how much of this reply came before it. Socket
         * thread. The listener may modify [pcm] in place. */
        fun onAudio(pcm: ByteArray, bytesBefore: Long)

        /** A few words the model heard ([input]) or is saying; [audioBytes] is the reply audio delivered so far. */
        fun onTranscript(text: String, input: Boolean, audioBytes: Long)

        /** The model's turn is over. [audioBytes] is 0 for the turn that only called the face tool. */
        fun onTurnComplete(audioBytes: Long)

        fun onInterrupted()

        /** The server will close the socket in about [timeLeftMs]. */
        fun onGoAway(timeLeftMs: Long)

        /** The socket is gone. [failure] is null when [close] asked for it. */
        fun onClosed(failure: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val request = Request.Builder().url("$ENDPOINT?key=$apiKey").build()
    private var socket: WebSocket? = null

    /** True once the server accepted the setup. Audio sent before that waits in [pending]. */
    @Volatile var ready = false
        private set

    /** The latest handle the server issued; pass it to [Personality.setup] to continue this conversation. */
    @Volatile var resumptionHandle: String? = null
        private set

    /** Whether this session was opened with a handle from an earlier one. */
    val resumed: Boolean = setup.optJSONObject("setup")?.optJSONObject("sessionResumption")?.has("handle") == true

    @Volatile private var closing = false
    private var reported = false
    private val pending = ArrayDeque<String>()   // guarded by this
    private var audioBytes = 0L                  // socket thread only

    fun connect() {
        socket = http.newWebSocket(request, Socket())
    }

    /** 100 ms or so of 16 kHz mono 16-bit PCM from the microphone. Any thread. */
    fun sendAudio(pcm: ByteArray) {
        val audio = JSONObject()
            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000")
        send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
    }

    /** A typed prompt, as a complete user turn. */
    fun sendText(text: String) {
        Log.d(TAG, "-> text turn: \"$text\"")
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        val content = JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)
        send(JSONObject().put("clientContent", content).toString())
    }

    fun close() {
        closing = true
        val s = socket
        if (s == null) report(null) else s.close(1000, "bye")
    }

    private fun send(message: String) {
        synchronized(this) {
            if (!ready) {
                pending.addLast(message)
                while (pending.size > PENDING_MAX) pending.removeFirst()
                return
            }
        }
        val queued = socket?.send(message) == true
        if (!queued) Log.w(TAG, "message not sent: the socket is closed or its queue is full")
    }

    private fun report(failure: String?) {
        main.post {
            if (reported) return@post
            reported = true
            listener.onClosed(failure)
        }
    }

    // ---- the socket thread ----

    private fun handle(text: String) {
        val m = try {
            LiveMessage.parse(text)
        } catch (e: Exception) {
            Log.w(TAG, "unreadable message", e)
            return
        }
        m.error?.let { Log.e(TAG, "server error: $it") }
        if (m.audio.isEmpty()) Log.d(TAG, "<- ${text.take(160)}")
        if (m.setupComplete) {
            val queued: List<String>
            synchronized(this) {
                ready = true
                queued = pending.toList()
                pending.clear()
            }
            queued.forEach { socket?.send(it) }
            Log.d(TAG, "setup accepted; ${queued.size} queued messages sent")
            main.post { listener.onReady() }
        }
        m.resumptionHandle?.let { resumptionHandle = it }

        for (b64 in m.audio) {
            val pcm = Base64.decode(b64, Base64.DEFAULT)
            val before = audioBytes
            audioBytes += pcm.size
            listener.onAudio(pcm, before)
        }
        m.inputTranscript?.let { t -> val at = audioBytes; main.post { listener.onTranscript(t, true, at) } }
        m.outputTranscript?.let { t -> val at = audioBytes; main.post { listener.onTranscript(t, false, at) } }

        if (m.functionCalls.isNotEmpty()) {
            val responses = JSONArray()
            for (call in m.functionCalls) {
                if (call.name == Personality.FACES_TOOL) {
                    val faces = Faces.parse(call.args)
                    main.post { listener.onFaces(faces) }
                } else {
                    Log.w(TAG, "the model called an unknown tool: ${call.name}")
                }
                responses.put(
                    JSONObject().put("id", call.id).put("name", call.name).put("response", JSONObject().put("result", "ok")),
                )
            }
            socket?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
        }

        if (m.interrupted) main.post { listener.onInterrupted() }
        if (m.turnComplete) {
            val at = audioBytes
            audioBytes = 0
            main.post { listener.onTurnComplete(at) }
        }
        m.goAwayMs?.let { ms -> main.post { listener.onGoAway(ms) } }
    }

    private inner class Socket : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setup.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The server is hanging up (setup rejected, quota, goAway). Finish the handshake.
            webSocket.close(1000, null)
            if (!closing) report("server closed the session: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            report(if (closing) null else "closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Never log the request: the key is in its URL.
            val status = response?.code?.let { "HTTP $it " } ?: ""
            report(if (closing) null else "$status${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "LiveSession"
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Microphone chunks kept while the socket opens: about six seconds. */
        private const val PENDING_MAX = 60

        /** No read timeout: the socket sits idle between exchanges. Pings keep it open. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
