package com.asciiwallpaper

/**
 * The Android logo used by fastfetch's `android` logo preset.
 *
 * Both the fastfetch information screen and the standalone Android style use
 * these exact lines. Keeping one shared source avoids the previous situation
 * where the information screen and the standalone Android style showed two
 * different, barely recognisable robots.
 *
 * fastfetch stores a few ANSI colour switches inside its logo file. They are
 * stripped here because the wallpaper applies the user's glyph colour to
 * every character.
 */
object AndroidAsciiArt {
    val LINES = arrayOf(
        "         -o          o-",
        "          +hydNNNNdyh+",
        "        +mMMMMMMMMMMMMm+",
        "      `dMMm:NMMMMMMN:mMMd`",
        "      hMMMMMMMMMMMMMMMMMMh",
        "  ..  yyyyyyyyyyyyyyyyyyyy  ..",
        ".mMMm`MMMMMMMMMMMMMMMMMMMM`mMMm.",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
        "-MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM-",
        " +yy+ MMMMMMMMMMMMMMMMMMMM +yy+",
        "      mMMMMMMMMMMMMMMMMMMm",
        "      `/++MMMMh++hMMMM++/`",
        "          MMMMo  oMMMM",
        "          MMMMo  oMMMM",
        "          oNMm-  -mMNs"
    )

    val WIDTH: Int = LINES.maxOf { it.length }
    val HEIGHT: Int = LINES.size
}
