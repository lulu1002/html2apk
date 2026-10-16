package com.vpsmanager

import androidx.compose.ui.graphics.Color

/** Tema de cores do terminal: fundo, texto e as 16 cores ANSI. */
class TerminalTheme(
    val name: String,
    val bg: Int,
    val fg: Int,
    private val palette: IntArray
) {
    /** Cor ANSI 0..15 (0-7 normais, 8-15 brilhantes). */
    fun ansi(i: Int): Color = Color(palette[i])
}

private fun c(rgb: Int): Int = (0xFF shl 24) or rgb

object TerminalThemes {
    val all: List<TerminalTheme> = listOf(
        TerminalTheme(
            "Padrão escuro", c(0x25232A), c(0xE6E1E5),
            intArrayOf(
                c(0x7F7F7F), c(0xE06C75), c(0x98C379), c(0xE5C07B),
                c(0x61AFEF), c(0xC678DD), c(0x56B6C2), c(0xDCDFE4),
                c(0xA0A0A0), c(0xFF7B86), c(0xB5E890), c(0xFFD98E),
                c(0x7FC4FF), c(0xE08FFF), c(0x6FD3E0), c(0xFFFFFF)
            )
        ),
        TerminalTheme(
            "Dracula", c(0x282A36), c(0xF8F8F2),
            intArrayOf(
                c(0x6272A4), c(0xFF5555), c(0x50FA7B), c(0xF1FA8C),
                c(0xBD93F9), c(0xFF79C6), c(0x8BE9FD), c(0xF8F8F2),
                c(0x7B8BC0), c(0xFF6E6E), c(0x69FF94), c(0xFFFFA5),
                c(0xD6ACFF), c(0xFF92DF), c(0xA4FFFF), c(0xFFFFFF)
            )
        ),
        TerminalTheme(
            "Solarized escuro", c(0x002B36), c(0x93A1A1),
            intArrayOf(
                c(0x657B83), c(0xDC322F), c(0x859900), c(0xB58900),
                c(0x268BD2), c(0xD33682), c(0x2AA198), c(0xEEE8D5),
                c(0x839496), c(0xE0524F), c(0xA6B800), c(0xD9A400),
                c(0x4AA3E8), c(0xE55C9E), c(0x3FBFB5), c(0xFDF6E3)
            )
        ),
        TerminalTheme(
            "Monokai", c(0x272822), c(0xF8F8F2),
            intArrayOf(
                c(0x75715E), c(0xF92672), c(0xA6E22E), c(0xE6DB74),
                c(0x66D9EF), c(0xAE81FF), c(0xA1EFE4), c(0xF8F8F2),
                c(0x8F8B77), c(0xFF4F8B), c(0xBDF24A), c(0xF2E88C),
                c(0x8AE2F5), c(0xC4A3FF), c(0xB8F5EC), c(0xFFFFFF)
            )
        ),
        TerminalTheme(
            "Nord", c(0x2E3440), c(0xD8DEE9),
            intArrayOf(
                c(0x616E88), c(0xBF616A), c(0xA3BE8C), c(0xEBCB8B),
                c(0x81A1C1), c(0xB48EAD), c(0x88C0D0), c(0xE5E9F0),
                c(0x7B88A1), c(0xD2757E), c(0xB5CCA0), c(0xF0D9A0),
                c(0x95B4D9), c(0xC6A1BF), c(0x8FBCBB), c(0xECEFF4)
            )
        ),
        TerminalTheme(
            "Claro", c(0xFAFAFA), c(0x24292E),
            intArrayOf(
                c(0x586069), c(0xD73A49), c(0x22863A), c(0xB08800),
                c(0x0366D6), c(0x6F42C1), c(0x1B7C83), c(0x6A737D),
                c(0x959DA5), c(0xCB2431), c(0x28A745), c(0xDBAB09),
                c(0x2188FF), c(0x8A63D2), c(0x3192AA), c(0x24292E)
            )
        ),
        TerminalTheme(
            "Matrix", c(0x000000), c(0x00FF41),
            intArrayOf(
                c(0x1F8F3A), c(0xFF5555), c(0x00FF41), c(0xE0E000),
                c(0x4DA6FF), c(0xFF4DFF), c(0x00E5E5), c(0xB8FFB8),
                c(0x2FBF55), c(0xFF7777), c(0x66FF8C), c(0xFFFF66),
                c(0x7FBFFF), c(0xFF80FF), c(0x66FFFF), c(0xFFFFFF)
            )
        )
    )
}
