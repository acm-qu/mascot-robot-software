package com.acmqu.acmo.gemini

import com.acmqu.acmo.face.Expression
import org.json.JSONArray
import org.json.JSONObject

/** A function the model wants called, with the id its answer must carry. */
data class FunctionCall(val id: String, val name: String, val args: JSONObject)

/**
 * One message from the Live server, reduced to the parts ACMO acts on. A
 * message can carry several of these at once, or none at all (the server does
 * send empty `{}` frames).
 */
data class LiveMessage(
    val setupComplete: Boolean = false,
    /** Base64 chunks of the model's speech: 24 kHz mono 16-bit PCM. */
    val audio: List<String> = emptyList(),
    /** A few words of what the model heard, as it hears them. */
    val inputTranscript: String? = null,
    /** A few words of what the model is saying, slightly ahead of the audio. */
    val outputTranscript: String? = null,
    val functionCalls: List<FunctionCall> = emptyList(),
    val generationComplete: Boolean = false,
    val turnComplete: Boolean = false,
    val interrupted: Boolean = false,
    /** A new handle for `sessionResumption`, whenever the server issues one. */
    val resumptionHandle: String? = null,
    /** The connection is about to be closed; this much time is left. */
    val goAwayMs: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun parse(json: String): LiveMessage {
            val o = JSONObject(json)
            val sc = o.optJSONObject("serverContent")

            val audio = mutableListOf<String>()
            sc?.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    if (data.optString("mimeType").startsWith("audio/")) audio.add(data.optString("data"))
                }
            }

            val calls = mutableListOf<FunctionCall>()
            o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    calls.add(FunctionCall(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject()))
                }
            }

            val resumption = o.optJSONObject("sessionResumptionUpdate")
            val handle = resumption?.takeIf { it.optBoolean("resumable", false) }
                ?.optString("newHandle")?.takeIf { it.isNotEmpty() }

            return LiveMessage(
                setupComplete = o.has("setupComplete"),
                audio = audio,
                inputTranscript = sc?.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() },
                outputTranscript = sc?.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() },
                functionCalls = calls,
                generationComplete = sc?.optBoolean("generationComplete") == true,
                turnComplete = sc?.optBoolean("turnComplete") == true,
                interrupted = sc?.optBoolean("interrupted") == true,
                resumptionHandle = handle,
                goAwayMs = o.optJSONObject("goAway")?.let { parseDuration(it.optString("timeLeft")) },
                error = o.optJSONObject("error")?.let { e -> e.optString("message").ifBlank { e.toString() } },
            )
        }

        /** A protobuf Duration in JSON: "59s", "0.5s". */
        fun parseDuration(s: String?): Long? =
            s?.trim()?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }
}

/** The argument of the face tool: the feelings the model planned, one per sentence. */
object Faces {
    /** Unknown feelings become happy; no feelings at all is one happy face. */
    fun parse(args: JSONObject): List<Expression> {
        val arr = args.optJSONArray("feelings") ?: JSONArray()
        val list = (0 until arr.length()).map { Expression.fromLabel(arr.optString(it)) ?: Expression.HAPPY }
        return list.ifEmpty { listOf(Expression.HAPPY) }
    }
}

/** Where one sentence ends and the face may change. */
object Sentence {
    private val enders = setOf('.', '!', '?', '؟', '؛', '。', '！', '？')

    fun endsIn(text: String): Boolean = text.any { it in enders }
}
