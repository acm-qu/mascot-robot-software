package com.acmqu.acmo.face

/**
 * The two palettes from the design. `ink` draws the eyes, brows and mouth,
 * `accent` the cheeks, badge and tear -- and accent is what the settings
 * panel's "primary colour" changes.
 */
data class FaceTheme(val bg: Int, val ink: Int, val accent: Int) {
    companion object {
        /** --primary-mid, the teal the light page uses. */
        const val TEAL_ON_LIGHT = 0xFF2FBBAB.toInt()
        /** --primary, the teal the dark page uses. */
        const val TEAL_ON_DARK = 0xFF3AE4D1.toInt()

        val LIGHT = FaceTheme(bg = 0xFFFBFAFB.toInt(), ink = 0xFF010000.toInt(), accent = TEAL_ON_LIGHT)
        val DARK = FaceTheme(bg = 0xFF010000.toInt(), ink = 0xFFFBFAFB.toInt(), accent = TEAL_ON_DARK)

        /** The base palette for the mode, with the accent swapped when the user picked one. */
        fun of(dark: Boolean, accent: Int?): FaceTheme {
            val base = if (dark) DARK else LIGHT
            return if (accent == null) base else base.copy(accent = accent)
        }
    }
}
