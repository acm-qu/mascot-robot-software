package com.acmqu.acmo.gemini

import com.acmqu.acmo.face.Expression
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One thing ACMO says, and the face it says it with. */
data class Segment(val feeling: Expression, val text: String)

/**
 * A whole reply: the segments in order, the language they are in, and the
 * interaction id that lets the next turn remember this one.
 */
data class Reply(val segments: List<Segment>, val language: String, val interactionId: String?) {
    companion object {
        /** Parses the model's JSON. A feeling outside the vocabulary becomes happy. */
        @Throws(JSONException::class)
        fun parse(json: String, interactionId: String?): Reply {
            val obj = JSONObject(json)
            val language = obj.optString("language", "en").ifBlank { "en" }
            val arr = obj.optJSONArray("segments") ?: JSONArray()
            val segments = (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = s.optString("text").trim()
                if (text.isEmpty()) null
                else Segment(Expression.fromLabel(s.optString("feeling")) ?: Expression.HAPPY, text)
            }
            if (segments.isEmpty()) throw JSONException("reply has no segments")
            return Reply(segments, language, interactionId)
        }
    }
}
