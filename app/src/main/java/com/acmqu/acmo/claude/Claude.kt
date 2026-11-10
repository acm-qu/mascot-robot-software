package com.acmqu.acmo.claude

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.gemini.Faces
import com.acmqu.acmo.gemini.Personality
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.json.JSONObject

/** What ACMO says next, and the face for each of its sentences. */
data class Answer(val feelings: List<Expression>, val text: String)

/**
 * Claude writes ACMO's words when the ElevenLabs voice speaks them; Gemini only listens then.
 * The answer streams: the faces come first, then the text a few words at a time, so the voice can
 * start on the first sentence while the rest is still being written. Blocking -- run it off the
 * main thread. The faces come back beside the text as JSON, so they never have to be read out of the words.
 */
class Claude(apiKey: String) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(1).build()

    /**
     * The answer to the last of [turns]: what the person said and ACMO answered, alternately, starting
     * and ending with the person. [onFeelings] hears the faces as soon as they are known (once, before
     * any text, unless the model wrote the text first), [onText] each piece of the text as it arrives;
     * both on this thread. Throws on any failure, including one thrown by the callbacks.
     */
    fun answer(turns: List<String>, onFeelings: (List<Expression>) -> Unit, onText: (String) -> Unit): Answer {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16_000L)
            .system(Personality.REPLY_SYSTEM)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).format(FORMAT).build())
            // Thinking off: with no tools, "between tools" is never. Thinking would only delay a short answer.
            .putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "between_tools")))
            // A refusal is retried on a fallback model by the API itself, rather than ending the exchange.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .apply {
                turns.forEachIndexed { i, t -> if (i % 2 == 0) addUserMessage(t) else addAssistantMessage(t) }
            }
            .build()
        val stream = AnswerStream(onFeelings, onText)
        client.messages().createStreaming(params).use { response ->
            response.stream()
                .flatMap { it.contentBlockDelta().stream() }
                .flatMap { it.delta().text().stream() }
                .forEach { stream.feed(it.text()) }
        }
        return parse(stream.whole)
    }

    companion object {
        /** No thinking, low effort: about 1.5 s to the first words of a short answer. */
        const val MODEL = "claude-sonnet-5-5"

        /** `{"feelings": [...], "text": "..."}`, the feelings limited to the faces there are. */
        private val FORMAT: JsonOutputFormat = JsonOutputFormat.builder()
            .schema(
                JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty(
                        "properties",
                        JsonValue.from(
                            mapOf(
                                "feelings" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to Personality.FEELINGS)),
                                "text" to mapOf("type" to "string"),
                            ),
                        ),
                    )
                    .putAdditionalProperty("required", JsonValue.from(listOf("feelings", "text")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()

        /** The model's JSON as an [Answer]. Unknown feelings become happy, as Gemini's do. */
        fun parse(json: String): Answer {
            val o = JSONObject(json)
            val text = o.optString("text").trim()
            require(text.isNotEmpty()) { "Claude answered with no text" }
            return Answer(Faces.parse(o), text)
        }
    }
}

/**
 * Follows the JSON answer as it streams in, a few characters at a time: hands on the feelings once the
 * text begins (they come first, in the schema's order), then the text itself, unescaped, as it grows.
 */
class AnswerStream(private val onFeelings: (List<Expression>) -> Unit, private val onText: (String) -> Unit) {
    private val json = StringBuilder()
    private var at = -1          // the next character of the text's value, once its key has been seen
    private var closed = false   // the text's closing quote has been read

    /** Everything received so far. */
    val whole: String get() = json.toString()

    fun feed(delta: String) {
        json.append(delta)
        if (at < 0) {
            val key = TEXT_KEY.find(json) ?: return
            at = key.range.last + 1
            feelingsBefore(key.range.first)
        }
        if (closed) return
        val out = StringBuilder()
        while (at < json.length) {
            val c = json[at]
            if (c == '"') {
                closed = true
                break
            }
            if (c != '\\') {
                out.append(c)
                at++
                continue
            }
            if (at + 1 >= json.length) break   // the rest of the escape is still on its way
            val e = json[at + 1]
            if (e == 'u') {
                if (at + 6 > json.length) break
                out.append(json.substring(at + 2, at + 6).toInt(16).toChar())   // a surrogate pair is two of these, in order
                at += 6
            } else {
                out.append(
                    when (e) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        else -> e   // \" \\ \/
                    },
                )
                at += 2
            }
        }
        if (out.isNotEmpty()) onText(out.toString())
    }

    /** `{"feelings": [...],` before the text's key, closed into an object. Nothing if the text came first. */
    private fun feelingsBefore(key: Int) {
        val head = runCatching { JSONObject(json.substring(0, key).trimEnd().removeSuffix(",") + "}") }.getOrNull() ?: return
        if (head.has("feelings")) onFeelings(Faces.parse(head))
    }

    private companion object {
        val TEXT_KEY = Regex("\"text\"\\s*:\\s*\"")
    }
}
