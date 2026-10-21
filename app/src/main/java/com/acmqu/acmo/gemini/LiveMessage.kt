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

    /**
     * Where the pause before each sentence after the first begins in [text]: the first space after
     * an ender, counted in code points, the way ElevenLabs times characters. An ender with no space
     * after it (3.14) ends nothing, and neither does one with nothing after its space.
     */
    fun pauses(text: String): List<Int> {
        val found = mutableListOf<Int>()
        var ended = false   // past an ender, and only enders since
        var pause = -1      // the first space after it, once there is one
        var at = 0          // code points so far
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            when {
                c < 0x10000 && c.toChar() in enders -> if (pause < 0) ended = true
                Character.isWhitespace(c) -> if (ended && pause < 0) pause = at
                else -> {
                    if (pause >= 0) found += pause
                    ended = false
                    pause = -1
                }
            }
            at++
            i += Character.charCount(c)
        }
        return found
    }

    /**
     * How many chars of [text], a transcript still arriving, are whole sentences: up to the last
     * ender followed by a space or by the end of the text so far. 0 when no sentence has ended yet.
     */
    fun whole(text: String): Int {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end > 0 && text[end - 1] in enders) return end
        for (i in end - 1 downTo 1) {
            if (text[i].isWhitespace() && text[i - 1] in enders) return i
        }
        return 0
    }
}
