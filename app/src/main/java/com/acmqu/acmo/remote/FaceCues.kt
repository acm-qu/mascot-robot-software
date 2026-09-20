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
 * A tag at index 0 is never a cue: the Brain shows that face from the start.
 */
class FaceCues(tags: List<FaceTag>) {
    private val tags = tags.filter { it.index > 0 }
    private var timed = 0   // characters timed so far
    private var next = 0

    /** Face tags the timing has not reached yet. */
    val pending: Int get() = tags.size - next

    /**
     * [atByte] is where each of the next characters of the text begins in the audio, one entry
     * per character. Returns the cues now known, oldest first, each rounded down to a whole frame.
     */
    fun feed(atByte: LongArray): List<Cue> {
        val start = timed
        timed += atByte.size
        if (next >= tags.size || tags[next].index >= timed) return emptyList()
        val found = ArrayList<Cue>(1)
        while (next < tags.size && tags[next].index < timed) {
            val tag = tags[next++]
            found += Cue(atByte[tag.index - start] and 1L.inv(), tag.feeling)
        }
        return found
    }
}
