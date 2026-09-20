package com.acmqu.acmo.remote

import com.acmqu.acmo.face.Expression

/** A face to show once playback reaches [atByte] of the line's audio. */
data class Cue(val atByte: Long, val feeling: Expression)

/**
 * Turns the timing ElevenLabs streams into face cues: fed where each character of the text begins
 * in the audio, it answers with the face tags reached so far and where each begins -- the start
 * of the tag's `[`, which is the pause before the new tone. One thread at a time.
 *
 * [tags] must be in ascending [FaceTag.index] order, as [Tags.faces] returns them. A tag at
 * index 0 is never a cue: the Brain shows that face from the start.
 */
class FaceCues(tags: List<FaceTag>) {
    private val tags = tags.filter { it.index > 0 }
    private var timedChars = 0
    private var nextTag = 0

    /** Face tags the timing has not reached yet. */
    val pending: Int get() = tags.size - nextTag

    /**
     * [atByte] is where each of the next characters begins in the audio, one entry per character
     * counted the way ElevenLabs counts them (a code point), in order. Nothing here verifies that;
     * the count advances the position and the entry at a tag's index is its cue. Returns the cues
     * now known, oldest first, each rounded down to a whole frame.
     */
    fun feed(atByte: LongArray): List<Cue> {
        val start = timedChars
        timedChars += atByte.size
        // The common chunk: no tag in it, nothing to allocate.
        if (nextTag >= tags.size || tags[nextTag].index >= timedChars) return emptyList()
        val found = ArrayList<Cue>(1)
        while (nextTag < tags.size && tags[nextTag].index < timedChars) {
            val tag = tags[nextTag++]
            found += Cue(atByte[tag.index - start] and 1L.inv(), tag.feeling)
        }
        return found
    }
}
