package zordon.build

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Verifica front-matter, seções obrigatórias de SPEC e links internos.
 *
 * <p>É uma classe comum, e não a tarefa Gradle, porque a regra não mora no
 * controller (docs/process/code-standards.md §9): assim ela é testável com `new`,
 * sem subir um build.
 */
@Spec("SPEC-001")
class DocsValidator(
    private val docsRoot: File,
    private val rootDocs: List<File> = emptyList(),
    private val repositoryRoot: File = docsRoot.parentFile,
) {

    data class Report(val documents: Int, val internalLinks: Int, val problems: List<String>) {
        val ok: Boolean get() = problems.isEmpty()
    }

    fun validate(): Report {
        val docs = docsRoot.walkTopDown().filter { it.isFile && it.extension == "md" }.map(Markdown::parse).toList()
        val outside = rootDocs.filter(File::isFile).map(Markdown::parse)
        val all = docs + outside
        val byPath = all.associateBy { it.file.canonicalFile }
        val specIds = SpecIndex.read(docsRoot).keys

        val problems = mutableListOf<String>()
        docs.forEach { doc ->
            problems += checkFrontMatter(doc, specIds)
            if (doc.file.name.startsWith("SPEC-")) problems += checkSpecSections(doc)
        }
        all.forEach { doc -> problems += checkLinks(doc, byPath) }

        return Report(
            documents = all.size,
            internalLinks = all.sumOf { doc -> doc.links.count { !it.target.startsWith("http") } },
            problems = problems,
        )
    }

    private fun checkFrontMatter(doc: MarkdownDoc, specIds: Set<String>): List<String> {
        val where = relative(doc.file)
        val fm = doc.frontMatter ?: return listOf("$where: sem front-matter")
        val problems = mutableListOf<String>()

        REQUIRED_FIELDS.forEach { field ->
            if (fm.fields[field].isNullOrBlank()) problems += "$where: front-matter sem '$field'"
        }
        // Campo vazio já foi reportado como ausente: repetir o mesmo problema com
        // outra frase só faz o leitor procurar dois defeitos onde há um.
        fm.value("securityLevel")?.let {
            if (it !in SECURITY_LEVELS) problems += "$where: securityLevel inválido '$it'"
        }
        fm.value("updatedAt")?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                problems += "$where: updatedAt não é uma data ISO ('$it')"
            }
        }
        fm.value("specId")?.let {
            if (it != "null" && it !in specIds) problems += "$where: specId '$it' não corresponde a nenhuma SPEC"
        }
        return problems
    }

    private fun checkSpecSections(doc: MarkdownDoc): List<String> {
        val present = doc.headings.map { it.substringBefore(".").trim() }.toSet()
        val missing = (1..SPEC_SECTIONS).map(Int::toString).filterNot { it in present }
        return if (missing.isEmpty()) emptyList()
        else listOf(
            "${relative(doc.file)}: SPEC sem as seções ${missing.joinToString(", ")} " +
                "(seção vazia se preenche com \"Não se aplica\"; apagada reprova)"
        )
    }

    private fun checkLinks(doc: MarkdownDoc, byPath: Map<File, MarkdownDoc>): List<String> {
        val problems = mutableListOf<String>()
        doc.links.forEach { link ->
            val target = link.target
            if (target.startsWith("http") || target.startsWith("mailto:")) return@forEach

            val where = "${relative(doc.file)}:${link.line}"
            val path = target.substringBefore("#")
            val anchor = target.substringAfter("#", "")
            val targetFile =
                if (path.isEmpty()) doc.file else File(doc.file.parentFile, path).canonicalFile

            if (!targetFile.exists()) {
                problems += "$where: alvo inexistente '$target'"
                return@forEach
            }
            if (anchor.isEmpty() || targetFile.extension != "md") return@forEach

            val targetDoc = byPath[targetFile] ?: Markdown.parse(targetFile)
            if (anchor !in targetDoc.anchors) {
                problems += "$where: âncora inexistente '#$anchor' em ${relative(targetFile)}"
            }
        }
        return problems
    }

    private fun relative(file: File) =
        file.canonicalPath.removePrefix(repositoryRoot.canonicalPath + "/")

    private companion object {
        val REQUIRED_FIELDS = listOf(
            "document", "module", "section", "version", "updatedAt", "securityLevel", "tags", "specId"
        )
        val SECURITY_LEVELS = setOf("public", "internal", "restricted")

        /** As 17 seções de docs/process/spec-template.md. */
        const val SPEC_SECTIONS = 17
    }
}
