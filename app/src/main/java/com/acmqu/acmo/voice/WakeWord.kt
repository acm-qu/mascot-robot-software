package com.acmqu.acmo.voice

/**
 * The wake word, as the wake-word model can actually hear it.
 *
 * Vosk's small English model has a fixed vocabulary and "acmo" is not in it, so
 * the recogniser is given a grammar of sound-alikes plus `[unk]` for everything
 * else, and anything that decodes to one of them is the wake word. Measured on
 * synthesised speech from six voices: "hey ACMO" woke 11 of 18 tries and 24
 * decoy phrases woke 0 -- say the word, pause, then ask.
 *
 * Tuning: add a variant here if the model keeps hearing your "ACMO" as
 * something else (watch logcat for `wake heard:` lines), and flip
 * [acceptBareMo] if you would rather have more wakes than fewer false ones.
 */
object WakeWord {
    val VARIANTS = listOf("ack mo", "ak mo", "ac mo", "hack mo", "back mo", "act mo", "acme")

    /** Vosk's grammar: a JSON array of the phrases it may return. */
    val GRAMMAR: String = (VARIANTS + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

    /**
     * Some voices lose the "ac" and the model hears only "mo". Accepting that
     * catches them, and also "tell me more" every now and then. Off by default.
     */
    @Volatile
    var acceptBareMo = false

    private val trigger = Regex("""\b(ack|ak|ac|hack|back|act) mo\b|\bacme\b""")
    private val bareMo = Regex("""\bmo\b""")

    fun matches(recognised: String): Boolean =
        trigger.containsMatchIn(recognised) || (acceptBareMo && bareMo.containsMatchIn(recognised))
}
