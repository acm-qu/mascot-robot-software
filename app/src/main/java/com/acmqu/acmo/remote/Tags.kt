package com.acmqu.acmo.remote

import com.acmqu.acmo.face.Expression

/** A face tag in a line: its exact text, where it starts (counted in code points), and the face it names. */
data class FaceTag(val text: String, val index: Int, val feeling: Expression)

/**
 * `[sad]`, `[laughs]`, `[whispers]`: a word in brackets is an ElevenLabs v3 audio tag -- direction
 * for the voice, not text to say. One that names a face (a label, or one of the extra words in
 * [WORDS]) changes the face too, when the voice gets there. Every tag stays in the text sent to
 * ElevenLabs; nothing here strips or rewrites them.
 */
object Tags {
    /** Three more ways of saying each face, chosen to be directions v3 understands as well. Idle has only its label. */
    private val EXTRAS: Map<Expression, List<String>> = mapOf(
        Expression.HAPPY to listOf("laughs", "giggles", "cheerful"),
        Expression.SAD to listOf("crying", "gloomy", "disappointed"),
        Expression.ANGRY to listOf("shouting", "furious", "growls"),
        Expression.ANNOYED to listOf("sarcastic", "groans", "frustrated"),
        Expression.SURPRISED to listOf("gasps", "shocked", "amazed"),
        Expression.EXCITED to listOf("thrilled", "enthusiastic", "energetic"),
        Expression.PASSIONATE to listOf("loving", "romantic", "dramatic"),
    )

    /** Every word that names a face, lowercase: the eight labels and the extras. The console mirrors this table. */
    val WORDS: Map<String, Expression> = buildMap {
        for (e in Expression.entries) {
            put(e.label, e)
            EXTRAS[e]?.forEach { put(it, e) }
        }
    }

    /** A bracketed word: one to forty characters, no brackets or line breaks inside. */
    private val TAG = Regex("""\[([^\[\]\r\n]{1,40})\]""")

    /** Whether [text] has any tag at all, face or voice only: such a line goes to the expressive model. */
    fun hasTags(text: String): Boolean = TAG.containsMatchIn(text)

    /** The face tags of [text], in order. [FaceTag.index] is counted in code points, the way ElevenLabs counts characters. */
    fun faces(text: String): List<FaceTag> = TAG.findAll(text).mapNotNull { m ->
        WORDS[m.groupValues[1].trim().lowercase()]?.let { FaceTag(m.value, text.codePointCount(0, m.range.first), it) }
    }.toList()

    /** The face a line opens with, when it starts with a face tag -- other tags and spaces may come first, text may not. */
    fun opening(text: String): Expression? {
        var end = 0
        for (m in TAG.findAll(text)) {
            if (text.substring(end, m.range.first).isNotBlank()) return null
            WORDS[m.groupValues[1].trim().lowercase()]?.let { return it }
            end = m.range.last + 1
        }
        return null
    }
}
