package com.acmqu.acmo.face

/**
 * One eye's pose: scale of the 14x20 rounded rectangle, rotation in degrees,
 * and an offset in stage units (1 unit = 1 % of the stage width, the design's cqw).
 */
data class EyePose(val sx: Float, val sy: Float, val rot: Float, val dx: Float, val dy: Float)

/** A glyph in one of the fixed mark slots, with its transform in stage units. */
data class Mark(
    val glyph: String,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rot: Float = 0f,
    val scale: Float = 1f,
)

/** The seven places a glyph can appear. Their positions live in FaceView. */
enum class Slot { BROW_L, BROW_R, CHEEK_L, CHEEK_R, MOUTH, BADGE, TEAR }

/**
 * The eight expressions, ported row for row from the FACES table in
 * Robot Face.dc.html. The label is also the word the language model uses, so
 * the two vocabularies can never drift apart.
 */
enum class Expression(
    val label: String,
    val left: EyePose,
    val right: EyePose,
    val marks: Map<Slot, Mark>,
) {
    IDLE(
        "idle",
        EyePose(1f, 1f, 0f, 0f, 0f), EyePose(1f, 1f, 0f, 0f, 0f),
        mapOf(Slot.MOUTH to Mark("_", scale = 1.1f)),
    ),
    SURPRISED(
        "surprised",
        EyePose(1.3f, 1.08f, 0f, 0f, -2f), EyePose(1.3f, 1.08f, 0f, 0f, -2f),
        mapOf(
            Slot.MOUTH to Mark("o", scale = 1.2f, dy = 1f),
            Slot.BADGE to Mark("!", rot = 8f, scale = 1.1f),
        ),
    ),
    SAD(
        "sad",
        EyePose(1.1f, .5f, -12f, 0f, 3f), EyePose(1.1f, .5f, 12f, 0f, 3f),
        mapOf(
            Slot.BROW_L to Mark("/", dy = 7f),
            Slot.BROW_R to Mark("\\", dy = 7f),
            Slot.MOUTH to Mark("(", rot = 90f, scale = 1.3f, dy = 1f),
            Slot.TEAR to Mark(";"),
        ),
    ),
    HAPPY(
        "happy",
        EyePose(1.15f, .45f, 0f, 0f, -2f), EyePose(1.15f, .45f, 0f, 0f, -2f),
        mapOf(
            Slot.CHEEK_L to Mark(">"),
            Slot.CHEEK_R to Mark("<"),
            Slot.MOUTH to Mark(")", rot = 90f, scale = 1.3f),
        ),
    ),
    ANGRY(
        "angry",
        EyePose(1.1f, .5f, 18f, 1.5f, 1f), EyePose(1.1f, .5f, -18f, -1.5f, 1f),
        mapOf(
            Slot.BROW_L to Mark("\\", dy = 8f, scale = 1.1f),
            Slot.BROW_R to Mark("/", dy = 8f, scale = 1.1f),
            Slot.MOUTH to Mark("#", scale = 1.1f, dy = 1f),
        ),
    ),
    PASSIONATE(
        "passionate",
        EyePose(1.1f, .6f, 8f, 1f, 0f), EyePose(1.1f, .6f, -8f, -1f, 0f),
        mapOf(
            Slot.BADGE to Mark("<3", rot = -12f, scale = 1.1f),
            Slot.MOUTH to Mark(")", rot = 90f),
        ),
    ),
    ANNOYED(
        "annoyed",
        EyePose(1.1f, .28f, 0f, 5f, 0f), EyePose(1.1f, .42f, 0f, 5f, -.5f),
        mapOf(Slot.MOUTH to Mark("~", scale = 1.2f, dx = 4f)),
    ),
    EXCITED(
        "excited",
        EyePose(1.1f, 1.15f, 0f, 0f, -1.5f), EyePose(1.1f, 1.15f, 0f, 0f, -1.5f),
        mapOf(
            Slot.CHEEK_L to Mark(">", scale = 1.15f),
            Slot.CHEEK_R to Mark("<", scale = 1.15f),
            Slot.MOUTH to Mark("D", rot = 90f, scale = 1.3f, dy = 1f),
        ),
    );


    companion object {
        fun fromLabel(label: String?): Expression? =
            label?.trim()?.lowercase()?.let { l -> entries.firstOrNull { it.label == l } }
    }
}
