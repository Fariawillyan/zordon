package zordon.build

import java.io.File

/**
 * Constrói o índice SPEC ↔ código ↔ teste a partir do repositório
 * (docs/process/traceability.md §5) e encontra referência órfã.
 *
 * <p>Não há banco nem ferramenta externa: cada elo é uma referência textual
 * verificável, e o repositório é a fonte.
 */
@Spec("SPEC-001")
class TraceabilityIndexer(
    private val docsRoot: File,
    private val sources: List<File>,
    private val repositoryRoot: File = docsRoot.parentFile,
) {

    data class Report(
        val specs: Map<String, SpecDocument>,
        val implementations: Map<String, List<String>>,
        val validations: Map<String, List<String>>,
        val problems: List<String>,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    fun index(): Report {
        val specs = SpecIndex.read(docsRoot)
        val implementations = mutableMapOf<String, MutableList<String>>()
        val validations = mutableMapOf<String, MutableList<String>>()
        val problems = mutableListOf<String>()

        sources.filter { it.isFile && it.extension in SOURCE_EXTENSIONS }.forEach { file ->
            val where = relative(file)
            val text = file.readText()

            SPEC_ANNOTATION.findAll(text).forEach { match ->
                val id = match.groupValues[1]
                if (id !in specs) problems += "$where: @Spec(\"$id\") não corresponde a nenhuma SPEC"
                else implementations.getOrPut(id) { mutableListOf() } += where
            }
            CRITERION_ANNOTATION.findAll(text).forEach { match ->
                val (id, criterion) = match.destructured
                val spec = specs[id]
                when {
                    spec == null ->
                        problems += "$where: @AcceptanceCriteria(\"$id/$criterion\") referencia SPEC inexistente"
                    criterion !in spec.criteria ->
                        problems += "$where: $id não define o critério $criterion"
                    else -> validations.getOrPut("$id/$criterion") { mutableListOf() } += where
                }
            }
        }

        specs.values.filter { it.status == "DONE" }.forEach { spec ->
            spec.criteria.filterNot { "${spec.id}/$it" in validations }.sorted().forEach { criterion ->
                problems += "${relative(spec.file)}: ${spec.id} está DONE mas $criterion não tem teste ligado"
            }
        }

        return Report(specs, implementations, validations, problems)
    }

    fun toJson(report: Report): String = buildString {
        append("{\n  \"specs\": [\n")
        append(report.specs.values.sortedBy { it.id }.joinToString(",\n") { spec ->
            val files = report.implementations[spec.id].orEmpty()
            val criteria = spec.criteria.sorted().joinToString(", ") { criterion ->
                val tests = report.validations["${spec.id}/$criterion"].orEmpty()
                "\"$criterion\": [${tests.joinToString(", ") { "\"$it\"" }}]"
            }
            """    {"id": "${spec.id}", "status": "${spec.status}", "doc": "${relative(spec.file)}", """ +
                """"files": [${files.joinToString(", ") { "\"$it\"" }}], "criteria": {$criteria}}"""
        })
        append("\n  ]\n}\n")
    }

    private fun relative(file: File) =
        file.canonicalPath.removePrefix(repositoryRoot.canonicalPath + "/")

    private companion object {
        /** Python usa as mesmas marcas em comentários, Java e Kotlin em anotações. */
        val SOURCE_EXTENSIONS = setOf("java", "kt", "py")

        val SPEC_ANNOTATION = Regex("""@Spec\("(SPEC-\d+)"\)""")
        val CRITERION_ANNOTATION = Regex("""@AcceptanceCriteria\("(SPEC-\d+)/(CA-\d+)"\)""")
    }
}
