package com.acmqu.acmo.gemini

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The two Gemini calls, both through the Interactions API:
 * `gemini-3.5-transcribe` turns the recorded prompt into text, and
 * `gemini-3.5-flash-lite` answers it as structured JSON. `store` plus
 * `previous_interaction_id` give ACMO a memory of the conversation so far.
 */
class GeminiClient(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    class ApiException(message: String) : IOException(message)

    /** The words in the recording, or "" when it held none. */
    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", TRANSCRIBE_MODEL)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("type", "audio")
                        .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                        .put("mime_type", "audio/wav"),
                ),
            )
            .put(
                "generation_config",
                JSONObject().put(
                    "transcription_config",
                    JSONObject()
                        .put("custom_vocabulary", JSONArray(listOf("ACMO", "ACM")))
                        .put("mode", "smart"),
                ),
            )
        outputText(post(body)).trim()
    }

    /** ACMO's answer to [userText], continuing from [previousInteractionId] when there is one. */
    suspend fun reply(userText: String, previousInteractionId: String?): Reply = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", CHAT_MODEL)
            .put("system_instruction", Personality.SYSTEM)
            .put("input", userText)
            .put("store", true)
            .put("response_format", Personality.responseFormat())
            .put(
                "generation_config",
                JSONObject()
                    .put("thinking_level", THINKING_LEVEL)
                    .put("max_output_tokens", MAX_OUTPUT_TOKENS),
            )
        if (previousInteractionId != null) body.put("previous_interaction_id", previousInteractionId)

        val interaction = post(body)
        val text = outputText(interaction)
        if (text.isBlank()) throw ApiException("empty reply (status ${interaction.optString("status")})")
        Reply.parse(text, interaction.optString("id").ifBlank { null })
    }

    private fun post(body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}: ${text.take(300)}")
            return JSONObject(text)
        }
    }

    companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
        const val CHAT_MODEL = "gemini-3.5-flash-lite"
        /** minimal / low / medium / high. Minimal keeps a chat reply under two seconds. */
        const val THINKING_LEVEL = "minimal"
        const val MAX_OUTPUT_TOKENS = 600
        private val JSON = "application/json".toMediaType()

        /**
         * The text of the model_output step(s). Thought steps carry a signature
         * and no content; a silent recording yields a model_output with none.
         */
        fun outputText(interaction: JSONObject): String {
            val steps = interaction.optJSONArray("steps") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") sb.append(part.optString("text"))
                }
            }
            return sb.toString()
        }
    }
}
