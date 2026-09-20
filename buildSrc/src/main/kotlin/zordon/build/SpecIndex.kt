package zordon.build

import java.io.File

/** Uma SPEC como documento: o que o repositório sabe dela. */
data class SpecDocument(
    val id: String,
    val file: File,
    val status: String,
    val criteria: Set<String>,
    /** Critérios que outra SPEC superou: ficam no texto como história, sem exigir teste. */
    val superseded: Set<String> = emptySet(),
) {
    /** Os que ainda precisam de prova. */
    val activeCriteria: Set<String> get() = criteria - superseded
}

/** Lê as SPECs sob `docs/specs`, que são a fonte da rastreabilidade. */
object SpecIndex {

    private val STATUS = Regex("""\|\s*\*\*Status\*\*\s*\|\s*([A-Z_]+)""")
    private val CRITERION = Regex("""^[-*]\s+~*`?(CA-\d+)`?""")

    /**
     * Um critério superado é escrito riscado (`~~…~~`) e diz quem o superou. Ele
     * continua no documento — apagar seria perder o porquê —, mas deixa de exigir
     * teste, porque a prova passou a ser da SPEC que o substituiu.
     */
    private val SUPERSEDED = Regex("""^[-*]\s+~*`?(CA-\d+)`?~*\s*~~""")

    fun read(docsDir: File): Map<String, SpecDocument> {
        val specsDir = File(docsDir, "specs")
        if (!specsDir.isDirectory) return emptyMap()

        return specsDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("SPEC-") && it.extension == "md" }
            .mapNotNull(::readSpec)
            .associateBy(SpecDocument::id)
    }

    private fun readSpec(file: File): SpecDocument? {
        val doc = Markdown.parse(file)
        val id = doc.frontMatter?.fields?.get("specId")?.takeIf { it.startsWith("SPEC-") } ?: return null
        val text = file.readText()
        val status = STATUS.find(text)?.groupValues?.get(1) ?: "DRAFT"
        val lines = file.readLines().map { it.trim() }
        val criteria = lines.mapNotNull { CRITERION.find(it)?.groupValues?.get(1) }.toSet()
        val superseded = lines.mapNotNull { SUPERSEDED.find(it)?.groupValues?.get(1) }.toSet()
        return SpecDocument(id, file, status, criteria, superseded)
    }
}
