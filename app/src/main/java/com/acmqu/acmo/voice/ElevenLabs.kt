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
    interface Sink {
        /** Some of the audio, in order. OkHttp's thread. */
        fun play(pcm: ByteArray)

        /** The whole line has been delivered. */
        fun finish()

        /** No more audio is coming: an HTTP error, a timeout, a dropped connection. Never after a cancel. */
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
                        val why = errorMessage(r.body?.string())
                        Log.w(TAG, "HTTP ${r.code}: $why")
                        sink.fail("ElevenLabs ${r.code}: $why")
                        return
                    }
                    val input = r.body?.byteStream() ?: run {
                        sink.finish()
                        return
                    }
                    val buf = ByteArray(CHUNK_BYTES)
                    try {
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) sink.play(buf.copyOf(n))
                        }
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
                        ?: detail.toString()
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
