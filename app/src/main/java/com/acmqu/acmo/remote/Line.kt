package com.acmqu.acmo.remote

import com.acmqu.acmo.Brain
import com.acmqu.acmo.face.Expression
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One line for ACMO to say, and the face it makes while saying it. */
data class Line(val text: String, val feeling: Expression) {
    companion object {
        /** Longer than any line an operator types; far under ElevenLabs' limit. */
        const val MAX_CHARS = 2000
        val DEFAULT_FEELING = Expression.HAPPY
    }
}

/** The body of `POST /say`: the line, and whether it cuts in ([now]) or waits its turn. */
data class Say(val line: Line, val now: Boolean) {
    companion object {
        /** Throws [IllegalArgumentException] whose message is the 400 reply's `error`. */
        fun parse(json: String): Say {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                throw IllegalArgumentException("the body is not JSON")
            }
            val text = o.optString("text", "").trim()
            require(text.isNotEmpty()) { "text is empty" }
            require(text.length <= Line.MAX_CHARS) { "text is longer than ${Line.MAX_CHARS} characters" }
            val label = o.optString("feeling", "")
            val feeling = if (label.isBlank()) {
                Line.DEFAULT_FEELING
            } else {
                Expression.fromLabel(label) ?: throw IllegalArgumentException(
                    "unknown feeling \"${label.trim()}\"; one of ${Expression.entries.joinToString(" ") { it.label }}",
                )
            }
            return Say(Line(text, feeling), o.optBoolean("now", false))
        }
    }
}

/** A line once the Brain has it: numbered, so the console can tell them apart. */
data class Entry(val id: Int, val line: Line) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", line.text)
        .put("feeling", line.feeling.label)
}

/** The reply to `POST /say`: the line's id, and how many remote lines are ahead of it. */
data class Said(val id: Int, val queued: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("queued", queued)
}

/** The last line that could not be spoken, and why. */
data class Failure(val id: Int, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("message", message)
}

/** What `GET /state` reports: [line] is the remote line playing, null during a Gemini reply or when quiet. */
data class Snapshot(val state: Brain.State, val line: Entry?, val queue: List<Entry>, val error: Failure?) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name.lowercase())
        .put("line", line?.toJson() ?: JSONObject.NULL)
        .put("queue", JSONArray().apply { queue.forEach { put(it.toJson()) } })
        .put("error", error?.toJson() ?: JSONObject.NULL)
}
