package zordon.build

import java.io.File

/** Front-matter YAML (subconjunto de uma linha por chave) de um documento. */
data class FrontMatter(val fields: Map<String, String>, val endLine: Int) {

    /** O valor da chave, ou null quando ela está ausente ou vazia. */
    fun value(key: String): String? = fields[key]?.takeIf { it.isNotBlank() }
}

data class MarkdownDoc(
    val file: File,
    val frontMatter: FrontMatter?,
    val headings: List<String>,
    val anchors: Set<String>,
    val links: List<Link>,
)

data class Link(val target: String, val line: Int)

object Markdown {

    private val LINK = Regex("""\[[^\]]*]\(([^)\s]+)\)""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val FENCE = Regex("""^\s*```""")

    fun parse(file: File): MarkdownDoc {
        val lines = file.readLines()
        val fm = parseFrontMatter(lines)
        val headings = mutableListOf<String>()
        val links = mutableListOf<Link>()
        var inFence = false

        lines.forEachIndexed { index, raw ->
            if (FENCE.containsMatchIn(raw)) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (inFence) return@forEachIndexed
            if (fm != null && index <= fm.endLine) return@forEachIndexed

            HEADING.find(raw)?.let { headings += it.groupValues[2] }
            LINK.findAll(raw).forEach { links += Link(it.groupValues[1], index + 1) }
        }

        val anchors = headings.map(::slug).toMutableSet()
        // Âncoras repetidas ganham sufixo -1, -2… no GitHub.
        val seen = mutableMapOf<String, Int>()
        headings.forEach { heading ->
            val base = slug(heading)
            val count = seen.merge(base, 0) { old, _ -> old + 1 }!!
            if (count > 0) anchors += "$base-$count"
        }
        return MarkdownDoc(file, fm, headings, anchors, links)
    }

    private fun parseFrontMatter(lines: List<String>): FrontMatter? {
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        val close = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (close < 0) return null
        val fields = mutableMapOf<String, String>()
        for (i in 1..close) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0 && !line.startsWith(" ")) {
                fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }
        return FrontMatter(fields, close + 1)
    }

    /**
     * Reproduz a geração de âncora do GitHub. Cada espaço vira um hífen — colapsar
     * espaços consecutivos produz falso positivo em títulos com espaço duplo.
     * `_` é preservado: ele não é ênfase dentro de crase (`WSL_INTEROP`).
     */
    fun slug(heading: String): String {
        var text = heading.trim()
        text = Regex("""\[([^\]]*)]\([^)]*\)""").replace(text) { it.groupValues[1] }
        text = text.replace("`", "").replace("*", "")
        return buildString {
            for (ch in text.lowercase()) {
                when {
                    ch.isLetterOrDigit() || ch == '-' || ch == '_' -> append(ch)
                    ch == ' ' -> append('-')
                    else -> Unit
                }
            }
        }
    }
}
