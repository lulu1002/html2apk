package com.example.gerenciador

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/** Realce de sintaxe leve, baseado em regex (um único passe, sem dependências). */
object Highlighter {

    class Span(color: Int) : ForegroundColorSpan(color)

    private const val NEVER = "(?!)"
    private const val MAX_CHARS = 300_000

    // aspas simples/duplas (com escape), sem quebra de linha
    private const val STR = """(?:"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')"""
    // + crases (JS/Go/Kotlin raw etc.)
    private const val STR_C = """(?:"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)"""
    private val STR_PY = "(?:\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|" + STR + ")"

    private const val KEYWORDS =
        "abstract|as|async|await|break|case|catch|class|const|continue|def|default|do|else|enum|" +
        "export|extends|false|final|finally|for|fun|func|function|if|implements|import|in|interface|" +
        "let|new|null|object|override|package|private|protected|public|return|static|struct|super|" +
        "switch|this|throw|true|try|typeof|val|var|void|when|while|yield|int|long|float|double|" +
        "boolean|string|char|byte|short|bool|nil|use|mod|impl|fn|mut|pub|self|using|namespace|" +
        "include|define|typedef|unsigned|signed|sizeof|goto|delete|virtual|template|typename|from|" +
        "with|is|not|and|or|None|True|False|lambda|pass|raise|elif|except|global|del|assert|echo|" +
        "fi|then|esac|done|local|readonly"

    private const val SQL_KEYWORDS =
        "select|from|where|insert|into|values|update|set|delete|create|table|drop|alter|add|index|" +
        "join|left|right|inner|outer|on|group|by|order|having|limit|offset|union|distinct|as|and|" +
        "or|not|null|is|in|like|between|exists|case|when|then|else|end|primary|key|foreign|" +
        "references|default|unique|view|database|if|asc|desc|count|sum|avg|min|max"

    private fun build(c: String, s: String, k: String, n: String, x: String, opts: Set<RegexOption> = emptySet()) =
        Regex("($c)|($s)|($k)|($n)|($x)", opts + RegexOption.MULTILINE)

    private val NUM = """\b\d+(?:\.\d+)?\b|\b0x[0-9a-fA-F]+\b"""

    private val clike = build(
        """//[^\n]*|/\*[\s\S]*?\*/""", STR_C, """\b(?:$KEYWORDS)\b""", NUM, NEVER
    )
    private val hash = build(
        """#[^\n]*""", STR_PY, """\b(?:$KEYWORDS)\b""", NUM, NEVER
    )
    private val html = build(
        """<!--[\s\S]*?-->""", STR, """</?[A-Za-z][\w:-]*|/?>""", """&\w+;""", """\b[\w:-]+(?==)"""
    )
    private val json = build(
        NEVER,
        """(?:"(?:\\.|[^"\\\n])*"(?!\s*:))""",
        """\b(?:true|false|null)\b""",
        """-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""",
        """(?:"(?:\\.|[^"\\\n])*"(?=\s*:))"""
    )
    private val css = build(
        """/\*[\s\S]*?\*/""", STR, """@[\w-]+""",
        """#[0-9a-fA-F]{3,8}\b|-?\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms|pt)?""",
        """[\w-]+(?=\s*:)"""
    )
    private val md = build(
        """<!--[\s\S]*?-->""", """`[^`\n]+`""", """^#{1,6} [^\n]*""",
        """\*\*[^*\n]+\*\*""", """\[[^\]\n]*\]\([^)\n]*\)"""
    )
    private val sql = build(
        """--[^\n]*|/\*[\s\S]*?\*/""", STR, """\b(?:$SQL_KEYWORDS)\b""", NUM, NEVER,
        setOf(RegexOption.IGNORE_CASE)
    )

    private val byExt: Map<String, Regex> = buildMap {
        for (e in "js mjs ts tsx jsx java kt kts c h cpp hpp cc cs go rs php swift dart scala gradle".split(" ")) put(e, clike)
        for (e in "py sh bash zsh yml yaml rb pl toml ini conf properties env cfg r dockerfile makefile".split(" ")) put(e, hash)
        for (e in "html htm xml svg xhtml".split(" ")) put(e, html)
        put("json", json)
        for (e in "css scss".split(" ")) put(e, css)
        for (e in "md markdown".split(" ")) put(e, md)
        put("sql", sql)
    }

    fun supports(name: String): Boolean = langFor(name) != null

    private fun langFor(name: String): Regex? {
        val n = name.lowercase()
        val ext = if (n.contains('.')) n.substringAfterLast('.') else n
        return byExt[ext]
    }

    private class Palette(val comment: Int, val string: Int, val keyword: Int, val number: Int, val extra: Int)

    private val DARK = Palette(0xFF6A9955.toInt(), 0xFFCE9178.toInt(), 0xFF569CD6.toInt(), 0xFFB5CEA8.toInt(), 0xFF9CDCFE.toInt())
    private val LIGHT = Palette(0xFF008000.toInt(), 0xFFA31515.toInt(), 0xFF0000FF.toInt(), 0xFF098658.toInt(), 0xFF795E26.toInt())

    fun apply(text: Editable, fileName: String, dark: Boolean) {
        for (s in text.getSpans(0, text.length, Span::class.java)) text.removeSpan(s)
        val lang = langFor(fileName) ?: return
        if (text.length > MAX_CHARS) return
        val p = if (dark) DARK else LIGHT
        val colors = intArrayOf(p.comment, p.string, p.keyword, p.number, p.extra)
        for (m in lang.findAll(text)) {
            for (i in 1..5) {
                val g = m.groups[i] ?: continue
                text.setSpan(Span(colors[i - 1]), g.range.first, g.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                break
            }
        }
    }
}
