package zordon.build

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceabilityIndexerTest {

    private val repository: File = Files.createTempDirectory("zordon-trace").toFile()
    private val docs: File = File(repository, "docs/specs/core").apply { mkdirs() }.parentFile.parentFile

    @AfterTest
    fun cleanUp() {
        repository.deleteRecursively()
    }

    @AcceptanceCriteria("SPEC-001/CA-3")
    @Test
    fun `anotacao que aponta para spec inexistente reprova`() {
        val source = source("Orfa.java", implementa("SPEC-099") + " class Orfa {}")

        val problem = index(source).single()

        assertTrue(problem.contains("não corresponde a nenhuma SPEC"), problem)
    }

    @AcceptanceCriteria("SPEC-001/CA-3")
    @Test
    fun `teste que aponta para criterio inexistente reprova`() {
        spec("SPEC-010", status = "APPROVED", criteria = listOf("CA-1"))
        val source = source("Teste.java", valida("SPEC-010", "CA-9") + " void teste() {}")

        assertTrue(index(source).single().contains("não define o critério CA-9"))
    }

    @AcceptanceCriteria("SPEC-001/CA-4")
    @Test
    fun `spec pronta com criterio sem teste reprova`() {
        spec("SPEC-010", status = "DONE", criteria = listOf("CA-1", "CA-2"))
        val source = source("Teste.java", valida("SPEC-010", "CA-1") + " void teste() {}")

        val problem = index(source).single()

        assertTrue(problem.contains("está DONE mas CA-2 não tem teste ligado"), problem)
    }

    @AcceptanceCriteria("SPEC-001/CA-4")
    @Test
    fun `spec em implementacao nao exige teste para todo criterio`() {
        // O portão vale na saída, não durante o trabalho: cobrar antes seria cobrar
        // teste de código que ainda não existe.
        spec("SPEC-010", status = "IMPLEMENTING", criteria = listOf("CA-1", "CA-2"))

        assertEquals(emptyList(), index(source("Vazio.java", "class Vazio {}")))
    }

    @AcceptanceCriteria("SPEC-001/CA-3")
    @Test
    fun `indice liga codigo e teste a spec`() {
        spec("SPEC-010", status = "DONE", criteria = listOf("CA-1"))
        val main = source("Motor.java", implementa("SPEC-010") + " class Motor {}")
        val test = source("MotorTest.java", valida("SPEC-010", "CA-1") + " void ok() {}")

        val indexer = TraceabilityIndexer(docs, listOf(main, test), repository)
        val report = indexer.index()

        assertEquals(emptyList(), report.problems)
        assertEquals(listOf("Motor.java"), report.implementations["SPEC-010"])
        assertTrue(indexer.toJson(report).contains("\"SPEC-010\""))
    }

    @AcceptanceCriteria("SPEC-001/CA-3")
    @Test
    fun `o build-logic em kotlin tambem entra no indice`() {
        spec("SPEC-010", status = "APPROVED", criteria = listOf("CA-1"))
        val kotlinTest = source("Teste.kt", valida("SPEC-010", "CA-1") + " fun ok() {}")

        assertEquals(emptyList(), index(kotlinTest))
    }

    @Test
    fun `testes python com marcas em comentarios cobrem criterio de spec pronta`() {
        spec("SPEC-010", status = "DONE", criteria = listOf("CA-1"))
        val python = source("test_voice.py", "# " + valida("SPEC-010", "CA-1") + "\ndef test_voice(): pass")
        val report = TraceabilityIndexer(docs, listOf(python), repository).index()
        assertTrue(report.ok, report.problems.toString())
        assertEquals(listOf("test_voice.py"), report.validations["SPEC-010/CA-1"])
    }

    /** As marcas das fixtures são interpoladas para não entrarem no índice real. */
    private fun implementa(id: String) = """@Spec("$id")"""

    private fun valida(id: String, criterion: String) = """@AcceptanceCriteria("$id/$criterion")"""

    private fun index(vararg sources: File): List<String> =
        TraceabilityIndexer(docs, sources.toList(), repository).index().problems

    private fun source(name: String, content: String): File =
        File(repository, name).apply { writeText(content) }

    private fun spec(id: String, status: String, criteria: List<String>) {
        File(docs, "specs/core/$id-teste.md").writeText(
            """
            ---
            document: spec
            module: core
            section: spec
            version: 1
            updatedAt: 2026-09-17
            securityLevel: public
            tags: [teste]
            specId: $id
            ---

            # $id — Teste

            | Campo | Valor |
            |---|---|
            | **Status** | $status |

            ## 15. Critérios de aceite

            ${criteria.joinToString("\n            ") { "- `$it` Alguma coisa verificável." }}
            """.trimIndent()
        )
    }
}
