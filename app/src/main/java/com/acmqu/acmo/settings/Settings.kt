package com.acmqu.acmo.settings

import android.content.Context
import com.acmqu.acmo.face.FaceTheme

/** A primary colour the user can pick. `color == null` means the theme's own teal. */
data class Swatch(val name: String, val color: Int?)

/** What the settings panel changes, persisted in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("acmo", Context.MODE_PRIVATE)

    var dark: Boolean
        get() = prefs.getBoolean("dark", false)
        set(v) = prefs.edit().putBoolean("dark", v).apply()

    /** Shows the text box and mic button along the bottom of the face. */
    var devMode: Boolean
        get() = prefs.getBoolean("dev", false)
        set(v) = prefs.edit().putBoolean("dev", v).apply()

    /**
     * Listens for the remote console (software/remote) on [com.acmqu.acmo.remote.RemoteServer.PORT].
     * On by default, and unauthenticated: whoever is on the Wi-Fi can make ACMO talk while it is on.
     */
    var remote: Boolean
        get() = prefs.getBoolean("remote", true)
        set(v) = prefs.edit().putBoolean("remote", v).apply()

    /** Index into [SWATCHES]. */
    var swatch: Int
        get() = prefs.getInt("swatch", 0).coerceIn(0, SWATCHES.size - 1)
        set(v) = prefs.edit().putInt("swatch", v).apply()

    /** Window brightness 0.05..1, or [BRIGHTNESS_SYSTEM] to leave the system's alone. */
    var brightness: Float
        get() = prefs.getFloat("brightness", BRIGHTNESS_SYSTEM)
        set(v) = prefs.edit().putFloat("brightness", v).apply()

    fun theme(): FaceTheme = FaceTheme.of(dark, SWATCHES[swatch].color)

    companion object {
        const val BRIGHTNESS_SYSTEM = -1f

        /** The brand teal first, then the department colours from the design system, then three more. */
        val SWATCHES = listOf(
            Swatch("ACM teal", null),
            Swatch("lime", 0xFF67FA4D.toInt()),
            Swatch("sky", 0xFF84E0FA.toInt()),
            Swatch("magenta", 0xFFE803FC.toInt()),
            Swatch("red", 0xFFFA4D4D.toInt()),
            Swatch("amber", 0xFFFFB000.toInt()),
            Swatch("coral", 0xFFFF6B6B.toInt()),
            Swatch("violet", 0xFFA78BFA.toInt()),
        )
    }
}
