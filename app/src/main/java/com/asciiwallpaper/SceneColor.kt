package com.asciiwallpaper

/**
 * Scene-side brightness helper for the hand-drawn DNA and black-hole passes.
 *
 * The scene is intentionally rendered in neutral white. `AsciiFilter` samples
 * its luminance and paints ASCII glyphs or Nothing-style dots with the custom
 * glyph colour, so every scene follows the same colour controls.
 */
internal object SceneColor {
    const val WHITE: Int = 0xFFF4FFF4.toInt()

    fun gray(brightness: Float, alpha: Int = 255): Int {
        val value = (brightness.coerceIn(0f, 1f) * 255f).toInt()
        val a = alpha.coerceIn(0, 255)
        return (a shl 24) or (value shl 16) or (value shl 8) or value
    }
}
