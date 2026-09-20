package com.acmqu.acmo.voice

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs text-to-speech, streamed. [stream] asks for a line as raw
 * 24 kHz mono 16-bit PCM -- exactly what [AudioOut] plays, so nothing is
 * decoded -- and hands the bytes to a [Sink] chunk by chunk as they arrive,
 * on OkHttp's thread. The first chunk is usually heard within half a second.
 *
 * The client keeps its TLS connection to ElevenLabs alive between lines, so
 * only the first line after a few minutes' quiet pays for the handshake.
 */
class ElevenLabs(
    private val apiKey: String,
    voiceId: String,
    private val http: OkHttpClient = defaultClient,
    baseUrl: String = BASE_URL,
) {
    /** Where the audio goes. Implementations must not throw: an exception here escapes on OkHttp's thread. */
    interface Sink {
        /** Some of the audio, in order. OkHttp's thread. */
        fun play(pcm: ByteArray)

        /** The whole line has been delivered. */
        fun finish()

        /** No more audio is coming: an HTTP error, a timeout, a dropped connection. Not after a cancel, apart from one that lands mid-read. */
        fun fail(message: String)
    }

    private val url = "$baseUrl/v1/text-to-speech/$voiceId/stream?output_format=$OUTPUT_FORMAT"

    /** Starts streaming [text] into [sink]. Cancel the returned call to stop; the sink then hears nothing more. */
    fun stream(text: String, sink: Sink): Call {
        val body = JSONObject().put("text", text).put("model_id", MODEL).toString()
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .post(body.toRequestBody(JSON))
            .build()
        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                Log.w(TAG, "request failed", e)
                sink.fail("ElevenLabs: ${e.message ?: e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        // Bounded and exception-safe: a torn or slow error body must still end in fail(),
                        // because an exception escaping this callback is swallowed by OkHttp, not reported.
                        val why = errorMessage(runCatching { r.peekBody(ERROR_BODY_BYTES).string() }.getOrNull())
                        Log.w(TAG, "HTTP ${r.code}: $why")
                        sink.fail("ElevenLabs ${r.code}: $why")
                        return
                    }
                    Log.d(TAG, "${r.protocol} ${r.code} ${r.header("content-type")}")
                    val input = r.body?.byteStream() ?: run {
                        if (!call.isCanceled()) sink.finish()
                        return
                    }
                    // A frame is two bytes and a read ends anywhere, so an odd byte is held back for
                    // the next read: the player must only ever see whole frames.
                    val buf = ByteArray(CHUNK_BYTES)
                    var held = 0
                    var total = 0L
                    try {
                        while (true) {
                            val n = input.read(buf, held, buf.size - held)
                            if (n < 0) break
                            if (n == 0) continue
                            val have = held + n
                            val whole = have and 1.inv()
                            if (whole > 0) sink.play(buf.copyOf(whole))
                            total += whole
                            held = have - whole
                            if (held > 0) buf[0] = buf[whole]
                        }
                        Log.d(TAG, "body ended: $total bytes, ${total / (AudioOut.BYTES_PER_MS * 1000)} s of audio")
                    } catch (e: IOException) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "stream ended early", e)
                        sink.fail("ElevenLabs: ${e.message ?: e.javaClass.simpleName}")
                        return
                    }
                    if (!call.isCanceled()) sink.finish()
                }
            }
        })
        return call
    }

    companion object {
        private const val TAG = "ElevenLabs"
        const val BASE_URL = "https://api.elevenlabs.io"

        /** The lowest-latency model (about 75 ms), 32 languages including Arabic. */
        const val MODEL = "eleven_flash_v2_5"

        /** Raw 24 kHz mono 16-bit PCM: what AudioOut plays, available on every tier. */
        const val OUTPUT_FORMAT = "pcm_24000"

        /** Jessica -- "playful, bright, warm" -- one of ElevenLabs' stock voices. */
        const val DEFAULT_VOICE_ID = "cgSgspJ2msm6clMCkdW9"

        /** About 85 ms of audio per read. */
        const val CHUNK_BYTES = 4096

        /** As much of an error body as is worth reading; the message is truncated to 200 characters anyway. */
        const val ERROR_BODY_BYTES = 4096L

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * ElevenLabs' error body: `{"detail": {"status": ..., "message": ...}}`, or `{"detail": "..."}`,
         * or not JSON at all. Strings only: Android's org.json turns a JSON null into the word "null".
         */
        fun errorMessage(body: String?): String {
            if (body.isNullOrBlank()) return "no details"
            return try {
                when (val detail = JSONObject(body).opt("detail")) {
                    is JSONObject -> (detail.opt("message") as? String)?.ifBlank { null }
                        ?: (detail.opt("status") as? String)?.ifBlank { null }
                        ?: detail.toString().take(200)
                    is String -> detail
                    else -> body.take(200)
                }
            } catch (_: JSONException) {
                body.take(200)
            }
        }

        /** Short timeouts: a line that stalls for 15 s is dead, and the queue should move on. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
