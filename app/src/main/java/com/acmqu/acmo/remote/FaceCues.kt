package com.acmqu.acmo.remote

import com.acmqu.acmo.face.Expression

/** A face to show once playback reaches [atByte] of the line's audio. */
data class Cue(val atByte: Long, val feeling: Expression)

/**
 * Turns the timing ElevenLabs streams into face cues. Fed the byte offset at which each character
 * of the text begins, in order, it answers with the face tags reached so far and where in the
 * audio each begins -- the start of the tag's `[`, which is the pause before the new tone.
 * One thread at a time.
 *
 * [tags] must be in ascending [FaceTag.index] order, as [Tags.faces] returns them. The offsets
 * fed must be the line's characters one per code point, in order, the way ElevenLabs counts
 * them: only the count is checked. A tag at index 0 is never a cue: the Brain shows that face
 * from the start.
 */
class FaceCues(tags: List<FaceTag>) {
    private val tags = tags.filter { it.index > 0 }
    private var timedChars = 0   // characters timed so far
    private var nextTag = 0

    /** Face tags the timing has not reached yet. */
    val pending: Int get() = tags.size - nextTag

    /**
     * [atByte] is where each of the next characters of the text begins in the audio, one entry
     * per character. Returns the cues now known, oldest first, each rounded down to a whole frame.
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
