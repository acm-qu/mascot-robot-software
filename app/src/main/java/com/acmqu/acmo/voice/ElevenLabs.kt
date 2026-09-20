package com.acmqu.acmo.voice

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs text-to-speech, streamed with timing. [stream] asks for a line as raw 24 kHz mono
 * 16-bit PCM -- exactly what [AudioOut] plays, so nothing is decoded but base64 -- and hands the
 * bytes to a [Sink] chunk by chunk as they arrive, on OkHttp's thread, each chunk preceded by
 * where in the audio the characters it speaks begin. That timing is how a `[tag]` in the text
 * changes the face at the right moment. The first chunk is usually heard within half a second
 * from the fast model, within two from the expressive one.
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

        /**
         * Where in the audio each of the next characters of the text begins, one entry per character
         * as ElevenLabs counts them (a code point), delivered before the audio they describe.
         * OkHttp's thread. A sink that does not care about timing need not override it.
         */
        fun timed(atByte: LongArray) {}

        /** The whole line has been delivered. */
        fun finish()

        /**
         * No more audio is coming: an HTTP error, a timeout, a dropped connection, a chunk that made
         * no sense. Not after a cancel, apart from one that lands mid-read.
         */
        fun fail(message: String)
    }

    private val url = "$baseUrl/v1/text-to-speech/$voiceId/stream/with-timestamps?output_format=$OUTPUT_FORMAT"

    /**
     * Starts streaming [text] into [sink]. An [expressive] line goes to the v3 model, which reads a
     * `[tag]` as direction; a plain one to the fast model, which would read it out loud. Cancel the
     * returned call to stop; the sink then hears nothing more.
     */
    fun stream(text: String, sink: Sink, expressive: Boolean = false): Call {
        val model = if (expressive) EXPRESSIVE_MODEL else FAST_MODEL
        val body = JSONObject().put("text", text).put("model_id", model).toString()
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
                    Log.d(TAG, "${r.protocol} ${r.code} $model")
                    val reader = r.body?.charStream()?.buffered() ?: run {
                        if (!call.isCanceled()) sink.finish()
                        return
                    }
                    // One JSON object per line, a blank line between them; each carries some audio and,
                    // usually, the timing of the characters it speaks.
                    // A line is one object, some 30 KB at most in practice; a body with no line breaks would only grow until the read timeout.
                    var total = 0L
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            total += deliver(line, sink)
                        }
                        Log.d(TAG, "body ended: $total bytes, ${total / (AudioOut.BYTES_PER_MS * 1000)} s of audio")
                    } catch (e: IOException) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "stream ended early", e)
                        sink.fail("ElevenLabs: ${e.message ?: e.javaClass.simpleName}")
                        return
                    } catch (e: BadChunk) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "bad chunk: ${e.message}")
                        sink.fail("ElevenLabs: bad chunk")
                        return
                    } catch (e: Exception) {
                        // The sink's contract is not to throw; if it does, the line must still end.
                        if (call.isCanceled()) return
                        Log.e(TAG, "sink threw", e)
                        sink.fail("ElevenLabs: ${e.javaClass.simpleName}")
                        return
                    }
                    if (!call.isCanceled()) sink.finish()
                }
            }
        })
        return call
    }

    /** A line of the stream that is not what ElevenLabs documents. */
    private class BadChunk(message: String) : Exception(message)

    /** One object of the stream: its timing to the sink first, then its audio. Returns the audio's size in bytes. */
    private fun deliver(line: String, sink: Sink): Int {
        val o = try {
            JSONObject(line)
        } catch (_: JSONException) {
            throw BadChunk("not JSON: ${line.take(80)}")
        }
        val alignment = when (val a = o.opt("alignment")) {
            null, JSONObject.NULL -> null
            is JSONObject -> a
            else -> throw BadChunk("alignment is not an object")
        }
        if (alignment != null) {
            val chars = alignment.optJSONArray("characters") ?: throw BadChunk("an alignment without characters")
            val starts = alignment.optJSONArray("character_start_times_seconds")
                ?: throw BadChunk("an alignment without start times")
            if (chars.length() != starts.length()) throw BadChunk("${chars.length()} characters, ${starts.length()} times")
            if (starts.length() > 0) {
                val atByte = LongArray(starts.length()) { i ->
                    val seconds = starts.opt(i) as? Number ?: throw BadChunk("a start time that is not a number")
                    (seconds.toDouble() * BYTES_PER_SECOND).toLong() and 1L.inv()
                }
                sink.timed(atByte)
            }
        }
        // Timing without audio is not something ElevenLabs sends today, and not worth failing a line over.
        val audio = when (val a = o.opt("audio_base64")) {
            null, JSONObject.NULL -> return 0
            is String -> a
            else -> throw BadChunk("audio_base64 is not a string")
        }
        if (audio.isEmpty()) return 0
        val pcm = audio.decodeBase64()?.toByteArray() ?: throw BadChunk("audio that is not base64")
        if (pcm.isNotEmpty()) sink.play(pcm)
        return pcm.size
    }

    companion object {
        private const val TAG = "ElevenLabs"
        const val BASE_URL = "https://api.elevenlabs.io"

        /** The lowest-latency model (about 75 ms), 32 languages including Arabic. Reads a `[tag]` out loud. */
        const val FAST_MODEL = "eleven_flash_v2_5"

        /** Eleven v3: takes a `[tag]` as direction for the voice. Starts about a second later than [FAST_MODEL]. */
        const val EXPRESSIVE_MODEL = "eleven_v3"

        /** Raw 24 kHz mono 16-bit PCM -- AudioOut's rate, so the timing's bytes and the player's agree; available on every tier. */
        const val OUTPUT_FORMAT = "pcm_${AudioOut.SAMPLE_RATE}"

        /** Jessica -- "playful, bright, warm" -- one of ElevenLabs' stock voices. */
        const val DEFAULT_VOICE_ID = "cgSgspJ2msm6clMCkdW9"

        /** Seconds in the timing to bytes of the audio: 24 000 frames of two bytes a second. */
        const val BYTES_PER_SECOND = AudioOut.BYTES_PER_MS * 1000.0

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
