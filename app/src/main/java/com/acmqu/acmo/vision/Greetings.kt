package com.acmqu.acmo.vision

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.Line
import kotlin.random.Random

/**
 * What ACMO says when someone walks up: fixed lines, spoken word for word in the ElevenLabs voice,
 * the way a console line is. Fixed on purpose, for now: while the camera is being tuned, a greeting
 * should cost nothing and sound the same every time, so that what changes is only whether it fires.
 * Gemini-written greetings come later (see the face presence design, section 8).
 *
 * Every line ends by inviting "hey ACMO", because a greeting does not listen: the wake word is
 * still the way into a conversation. No line has a `[tag]`, so each goes to ElevenLabs' fast model
 * and starts in well under a second. Rewrite the lines freely; nothing else depends on their wording.
 */
object Greetings {
    val ONE: List<Line> = listOf(
        Line("Hey there! I'm ACMO. Say hey ACMO if you want to chat.", Expression.HAPPY),
        Line("Oh, hello! Come closer and say hey ACMO, I don't bite.", Expression.EXCITED),
        Line("Hi! I'm ACMO, the ACM robot. Say hey ACMO to talk to me.", Expression.HAPPY),
        Line("Welcome! Say hey ACMO and ask me anything.", Expression.PASSIONATE),
    )

    val GROUP: List<Line> = listOf(
        Line("Oh, a whole crowd! Hi everyone! Say hey ACMO and let's talk.", Expression.EXCITED),
        Line("Hello, hello! So many humans. One of you, say hey ACMO!", Expression.SURPRISED),
        Line("Hi team! I'm ACMO. Whoever's bravest, say hey ACMO.", Expression.HAPPY),
    )

    val SMILING: List<Line> = listOf(
        Line("Nice smile! I'm ACMO. Say hey ACMO to talk to me.", Expression.HAPPY),
        Line("Ooh, someone's happy to see me! Say hey ACMO!", Expression.EXCITED),
        Line("That smile just charged my battery. Say hey ACMO to chat!", Expression.PASSIONATE),
    )

    /** The lines that suit [a]: a group first, then a smile, then one person. */
    fun lines(a: Arrival): List<Line> = when {
        a.people > 1 -> GROUP
        a.smiling -> SMILING
        else -> ONE
    }

    /** A line for [a], never [last] again when there is another to choose. */
    fun pick(a: Arrival, last: String?, random: Random = Random.Default): Line {
        val all = lines(a)
        val fresh = all.filter { it.text != last }.ifEmpty { all }
        return fresh[random.nextInt(fresh.size)]
    }
}
