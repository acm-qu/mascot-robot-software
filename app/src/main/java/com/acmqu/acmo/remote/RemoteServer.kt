package com.acmqu.acmo.remote

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The console's way in: a small HTTP server on the tablet.
 *
 *   POST /say    {text, feeling, now}  -> {id, queued}                  a line for ACMO to say
 *   POST /stop                         -> {ok}                          be quiet, forget the queue
 *   GET  /state                        -> {state, face, line, queue, error}   what ACMO is doing
 *   GET  /                             -> this, in plain text
 *
 * NanoHTTPD answers each request on its own thread; the one call into the app
 * is run on the main thread through [onMain] and waited for, so the [Host]
 * (the Brain) stays main-thread-only. Every reply carries permissive CORS
 * headers, so a page served from anywhere on the network can call it.
 *
 * There is no authentication: whoever is on the Wi-Fi can make ACMO talk.
 */
class RemoteServer(
    private val port: Int,
    private val host: Host,
    /** Runs a block on the main thread. Tests pass `{ it() }`. */
    private val onMain: (() -> Unit) -> Unit,
    /** Without an ElevenLabs key, /say is refused up front with a 503 that says so. */
    private val hasKey: Boolean,
) : NanoHTTPD(port) {

    /** The Brain, as the server sees it. Main thread. */
    interface Host {
        fun say(line: Line, now: Boolean): Said
        fun hush()
        fun snapshot(): Snapshot
    }

    /** Listens, or says why it cannot. Safe to call again. */
    fun startListening(): Boolean {
        if (isAlive) return true
        return try {
            start(SOCKET_READ_TIMEOUT, true)
            Log.i(TAG, "listening on :$listeningPort")
            true
        } catch (e: IOException) {
            Log.e(TAG, "could not listen on :$port", e)
            stop()   // NanoHTTPD leaves the socket it failed to bind open; this closes it
            false
        }
    }

    fun stopListening() {
        if (!isAlive) return
        stop()
        Log.i(TAG, "stopped")
    }

    /** Replies are a few hundred bytes on a LAN: gzip buys nothing and, on a 204, breaks the framing. */
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: Exception) {
            Log.w(TAG, "${session.method} ${session.uri} failed", e)
            json(Response.Status.INTERNAL_ERROR, error("${e.javaClass.simpleName}: ${e.message}"))
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        // A JSON POST is preflighted with OPTIONS; this makes it once a day, not once a line.
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun route(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) {
            // Not read, so the connection is closed instead: the unread body would poison the next request on it.
            return json(Response.Status.PAYLOAD_TOO_LARGE, error("the body is too large")).apply { closeConnection(true) }
        }
        // Always drained: leftover body bytes would corrupt the next request on a kept-alive connection.
        val body = readBody(session, length)
        if (session.method == Method.OPTIONS) return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
        return when (session.method to session.uri) {
            Method.POST to "/say" -> say(body)
            Method.POST to "/stop" -> {
                onMainOrNull { host.hush() } ?: return notResponding()
                Log.i(TAG, "stop")
                json(Response.Status.OK, JSONObject().put("ok", true))
            }
            Method.GET to "/state" -> {
                val snapshot = onMainOrNull { host.snapshot() } ?: return notResponding()
                json(Response.Status.OK, snapshot.toJson())
            }
            Method.GET to "/" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", ABOUT)
            else -> json(Response.Status.NOT_FOUND, error("no such route"))
        }
    }

    private fun say(body: String): Response {
        if (!hasKey) return json(Response.Status.SERVICE_UNAVAILABLE, error(NO_KEY))
        val say = try {
            Say.parse(body)
        } catch (e: IllegalArgumentException) {
            return json(Response.Status.BAD_REQUEST, error(e.message ?: "bad request"))
        }
        val said = onMainOrNull { host.say(say.line, say.now) } ?: return notResponding()
        Log.i(TAG, "say${if (say.now) " now" else ""} #${said.id} (${say.line.feeling.label}): \"${say.line.text}\"")
        return json(Response.Status.OK, said.toJson())
    }

    /** The request body as UTF-8, whatever charset the header claims; empty when there is none. */
    private fun readBody(session: IHTTPSession, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var read = 0
        val input = session.inputStream
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    /** Runs [block] on the main thread and returns its result, or null if that took more than [MAIN_TIMEOUT_MS]. */
    private fun <T : Any> onMainOrNull(block: () -> T): T? {
        val done = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        var result: T? = null
        var failure: Exception? = null
        onMain {
            // Too late (the request timed out) or too dead (the server was stopped): the app must not act on it.
            if (abandoned.get() || !isAlive) {
                done.countDown()
                return@onMain
            }
            try {
                result = block()
            } catch (e: Exception) {
                failure = e
            } finally {
                done.countDown()
            }
        }
        if (!done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            abandoned.set(true)
            Log.w(TAG, "the main thread did not answer within $MAIN_TIMEOUT_MS ms")
            return null
        }
        failure?.let { throw it }
        return result
    }

    private fun notResponding() = json(Response.Status.SERVICE_UNAVAILABLE, error("the app is not responding"))

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())

    private fun error(message: String) = JSONObject().put("error", message)

    companion object {
        private const val TAG = "RemoteServer"
        const val PORT = 8765
        private const val MAIN_TIMEOUT_MS = 2000L
        /** Far above any line (Line.MAX_CHARS is 2 000); a bigger claim is refused before a byte is read. */
        private const val MAX_BODY_BYTES = 1 shl 20
        private const val NO_KEY = "no ElevenLabs key: add ELEVENLABS_API_KEY to local.properties and rebuild"
        private val ABOUT = """
            ACMO remote -- type a line, ACMO says it. The console is software/remote in the repo.

              POST /say    {"text": "...", "feeling": "happy", "now": false}  -> {"id": 7, "queued": 0}
              POST /stop                                                    -> {"ok": true}
              GET  /state                                                   -> {"state", "face", "line", "queue", "error"}

            feeling: idle surprised sad happy angry passionate annoyed excited (default happy)
            now: true cuts off whatever is playing; false waits its turn.
        """.trimIndent() + "\n"

        /** The tablet's IPv4 address on the local network (Wi-Fi first), or null. */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
