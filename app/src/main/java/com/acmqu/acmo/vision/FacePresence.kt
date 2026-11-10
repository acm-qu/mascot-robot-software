package com.acmqu.acmo.vision

import kotlin.math.abs

/**
 * One face in one camera frame, as plain numbers. [VisionPipeline] makes these from ML Kit's
 * faces so that nothing past it touches ML Kit or Android, and [FacePresence] can be tested
 * on the JVM.
 */
data class Seen(
    /** ML Kit's tracking ID: the same while the face stays in view. Null when tracking lost it. */
    val id: Int?,
    /** The face's width as a fraction of the (rotated) frame's width: bigger is closer. */
    val width: Float,
    /** Head turned left or right, in degrees; 0 is looking straight at the tablet. */
    val yaw: Float,
    /** Head tilted up or down, in degrees. */
    val pitch: Float,
    /** How likely the person is smiling, 0..1, or null when ML Kit could not tell. */
    val smiling: Float?,
)

/** Someone walked up to ACMO and looked at it: [people] faces, and whether any of them is smiling. */
data class Arrival(val people: Int, val smiling: Boolean)

/**
 * Decides, frame by frame, when someone has *arrived*: close to ACMO, looking at it, and staying
 * a moment, as opposed to walking past or glancing over from across the room. Each arrival is a
 * greeting, so the rules lean towards greeting too little rather than too often: a robot that
 * says hello to everyone who walks by is noise, one that misses a passer-by loses nothing.
 *
 * A person is a tracking ID. Once greeted, an ID is never greeted again; a whole group in view is
 * greeted together; and there is a [COOLDOWN_MS] between greetings, which also covers ML Kit
 * handing a face that left and came back a new ID. Everyone in view while ACMO is busy counts as
 * greeted, so the person who just talked to it does not get a hello when the answer ends.
 *
 * Main thread only, like the Brain that owns it. Times are whatever clock the caller uses, in ms.
 */
class FacePresence {

    /** A face that qualifies and is not greeted yet: since when it has qualified, and when it last did. */
    private class Track(val since: Long, var last: Long)

    private val tracks = HashMap<Int, Track>()
    /** Greeted IDs, and when each was last seen. */
    private val greeted = HashMap<Int, Long>()
    private var lastArrival: Long? = null

    /**
     * One analysed frame: every face in it, at [atMs]. [busy] means ACMO is doing something other
     * than waiting, so nobody may be greeted now. Returns an arrival at most once per person.
     */
    fun feed(faces: List<Seen>, atMs: Long, busy: Boolean): Arrival? {
        // Forget first: a face back after FORGET_MS must not refresh itself before it is forgotten.
        greeted.entries.removeAll { atMs - it.value > FORGET_MS }
        for (f in faces) f.id?.let { if (it in greeted) greeted[it] = atMs }

        if (busy) {
            // Whoever is watching ACMO talk has, in effect, been greeted.
            for (f in faces) f.id?.let {
                greeted[it] = atMs
                tracks.remove(it)
            }
            return null
        }

        val close = faces.filter(::qualifies)
        for (f in close) {
            val id = f.id ?: continue
            if (id in greeted) continue
            val t = tracks[id]
            if (t == null || atMs - t.last > LOST_MS) tracks[id] = Track(atMs, atMs) else t.last = atMs
        }
        tracks.entries.removeAll { atMs - it.value.last > LOST_MS }

        // Someone in view right now has stayed long enough. Without the cooldown they keep their
        // place, and are greeted as soon as it has passed, if they are still there.
        val ready = tracks.values.any { it.last == atMs && atMs - it.since >= DWELL_MS }
        val rested = lastArrival.let { it == null || atMs - it >= COOLDOWN_MS }
        if (!ready || !rested) return null

        lastArrival = atMs
        for (f in faces) f.id?.let {
            greeted[it] = atMs
            tracks.remove(it)
        }
        return Arrival(people = close.size, smiling = close.any { (it.smiling ?: 0f) >= SMILING })
    }

    /** Forgets who is part-way through arriving. Who was greeted, and the cooldown, are kept. */
    fun reset() {
        tracks.clear()
    }

    private fun qualifies(f: Seen): Boolean =
        f.id != null && f.width >= MIN_WIDTH && abs(f.yaw) <= MAX_YAW && abs(f.pitch) <= MAX_PITCH

    companion object {
        /**
         * How close: the face's width as a fraction of the frame's. About 0.12 is arm's length to a
         * metre and a half on a tablet's front camera. Raise it if ACMO greets people across the room.
         */
        const val MIN_WIDTH = 0.12f

        /** How far the head may turn away (degrees) and still count as looking at ACMO. Lower it if passers-by get greeted. */
        const val MAX_YAW = 25f
        const val MAX_PITCH = 20f

        /** How long someone must stand there, looking, before ACMO says hello. */
        const val DWELL_MS = 800L

        /** A face may drop out of a few frames (a blink, a hand, a missed frame) without starting over. */
        const val LOST_MS = 1500L

        /** The least time between two greetings. */
        const val COOLDOWN_MS = 20_000L

        /** A greeted face unseen this long is forgotten, so the set stays small. ML Kit does not reuse IDs. */
        const val FORGET_MS = 60_000L

        /** ML Kit's smile probability above which a face counts as smiling. */
        const val SMILING = 0.7f
    }
}
