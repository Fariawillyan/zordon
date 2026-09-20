package zordon.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `cada espaco vira um hifen, inclusive espacos consecutivos`() {
        // O bug que este teste existe para impedir: colapsar espaços gerava
        // "#secao-titulo" onde o GitHub gera "#secao--titulo", e o verificador
        // acusava dezenas de links bons como quebrados.
        assertEquals("seção--título", Markdown.slug("Seção  Título"))
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `underscore sobrevive porque dentro de crase nao e enfase`() {
        assertEquals("wsl_interop-nao-existe", Markdown.slug("`WSL_INTEROP` nao existe"))
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `titulo com link usa o texto do link`() {
        assertEquals("ver-o-protocolo", Markdown.slug("Ver [o protocolo](../api/zwp-protocol.md)"))
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `acentos sao preservados como o github faz`() {
        assertEquals("permissão-e-risco", Markdown.slug("Permissão e risco"))
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `pontuacao e negrito somem do identificador`() {
        assertEquals("4-regras-de-dependência", Markdown.slug("4. **Regras** de dependência"))
    }

    @AcceptanceCriteria("SPEC-001/CA-1")
    @Test
    fun `front-matter e lido campo a campo`() {
        val file = document(
            """
            ---
            document: teste
            securityLevel: internal
            specId: SPEC-001
            ---

            # Título

            ## Uma seção
            """.trimIndent()
        )

        val doc = Markdown.parse(file)

        assertEquals("internal", doc.frontMatter?.fields?.get("securityLevel"))
        assertEquals("SPEC-001", doc.frontMatter?.fields?.get("specId"))
        assertTrue("uma-seção" in doc.anchors)
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `titulos repetidos ganham sufixo como no github`() {
        val doc = Markdown.parse(document("# A\n\n## Verificação\n\n## Verificação\n"))

        assertTrue("verificação" in doc.anchors)
        assertTrue("verificação-1" in doc.anchors)
    }

    @AcceptanceCriteria("SPEC-001/CA-2")
    @Test
    fun `titulo dentro de bloco de codigo nao vira ancora`() {
        val doc = Markdown.parse(document("# A\n\n```text\n# isto e um comentario, nao um titulo\n```\n"))

        assertEquals(listOf("A"), doc.headings)
    }

    private fun document(content: String): File =
        File.createTempFile("zordon-doc", ".md").apply {
            writeText(content)
            deleteOnExit()
        }
}
