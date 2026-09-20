package zordon.build

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocsValidatorTest {

    private val repository: File = Files.createTempDirectory("zordon-docs").toFile()
    private val docs: File = File(repository, "docs").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        repository.deleteRecursively()
    }

    @AcceptanceCriteria("SPEC-001/CA-1")
    @Test
    fun `documento sem campo obrigatorio reprova nomeando o campo`() {
        write("vision.md", frontMatter(updatedAt = "") + "\n# Visão\n")

        val problems = validate()

        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("front-matter sem 'updatedAt'"), problems.toString())
    }

    @AcceptanceCriteria("SPEC-001/CA-1")
    @Test
    fun `documento sem front-matter nenhum reprova`() {
        write("solto.md", "# Só o título\n")

        assertTrue(validate().single().contains("sem front-matter"))
    }

    @AcceptanceCriteria("SPEC-001/CA-1")
    @Test
    fun `nivel de sigilo inventado reprova`() {
        write("vision.md", frontMatter(securityLevel = "confidencialissimo") + "\n# Visão\n")

        assertTrue(validate().single().contains("securityLevel inválido"))
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `link para arquivo inexistente reprova com arquivo e linha`() {
        write("vision.md", frontMatter() + "\n# Visão\n\nVer [o mapa](arquitetura.md).\n")

        val problem = validate().single()

        assertTrue(problem.contains("docs/vision.md:"), problem)
        assertTrue(problem.contains("alvo inexistente"), problem)
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `link para ancora inexistente reprova`() {
        write("alvo.md", frontMatter() + "\n# Alvo\n\n## Uma seção\n")
        write("vision.md", frontMatter() + "\n# Visão\n\nVer [seção](alvo.md#outra-secao).\n")

        assertTrue(validate().single().contains("âncora inexistente"))
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `link valido para ancora existente passa`() {
        write("alvo.md", frontMatter() + "\n# Alvo\n\n## Uma seção\n")
        write("vision.md", frontMatter() + "\n# Visão\n\nVer [seção](alvo.md#uma-seção).\n")

        assertEquals(emptyList(), validate())
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `link externo nao e verificado`() {
        // Verificar http tornaria o build dependente de rede e vermelho por motivo
        // alheio ao repositório — que é como se ensina todo mundo a ignorar o build.
        write("vision.md", frontMatter() + "\n# Visão\n\n[fora](https://exemplo.invalido/nao-existe)\n")

        assertEquals(emptyList(), validate())
    }

    @AcceptanceCriteria("SPEC-001/CA-5")
    @Test
    fun `spec sem todas as secoes do template reprova listando o que falta`() {
        File(docs, "specs/core").mkdirs()
        write(
            "specs/core/SPEC-042-parcial.md",
            frontMatter(specId = "SPEC-042") + "\n# SPEC-042 — Parcial\n\n## 1. Objetivo\n\nUma frase.\n"
        )

        val problem = validate().single { it.contains("SPEC sem as seções") }

        assertTrue(problem.contains("2, 3, 4"), problem)
    }

    private fun validate(): List<String> =
        DocsValidator(docs, emptyList(), repository).validate().problems

    private fun write(path: String, content: String) {
        File(docs, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun frontMatter(
        updatedAt: String = "2026-09-17",
        securityLevel: String = "public",
        specId: String = "null",
    ) = """
        ---
        document: teste
        module: teste
        section: teste
        version: 1
        updatedAt: $updatedAt
        securityLevel: $securityLevel
        tags: [teste]
        specId: $specId
        ---
    """.trimIndent()
}
