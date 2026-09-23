/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zordon.core.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;
import zordon.memory.KnowledgeStore;
import zordon.memory.SqliteMemoryStore;
import zordon.memory.ZordonDatabase;

/** A documentação indexada e consultável com citação (SPEC-028). */
class KnowledgeBaseTest {

    @TempDir
    Path home;

    private ZordonDatabase db;
    private SqliteMemoryStore store;
    private KnowledgeBase knowledge;
    private Path docs;

    @BeforeEach
    void setUp() throws Exception {
        db = new ZordonDatabase(home.resolve("zordon.db"), Clock.systemUTC());
        store = db.memory();
        docs = home.resolve("docs/security");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("model.md"), """
                ---
                document: security-model
                securityLevel: restricted
                ---

                # Modelo de segurança

                Texto de abertura sobre o modelo.

                ## 7. Auditoria

                A auditoria é append-only: gatilhos recusam UPDATE e DELETE, e a cadeia de hash prova
                que nenhuma linha foi alterada depois de escrita.

                ### Retenção

                Nada é apagado.

                ## 8. Segredos

                O Redactor mascara o segredo antes de qualquer log.
                """);
        Files.writeString(home.resolve("docs/leia.md"), "# Leia-me\n\nO Zordon é um assistente residente.\n");
        knowledge = new KnowledgeBase(db.knowledge(), List.of(home.resolve("docs")));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @AcceptanceCriteria("SPEC-028/CA-1")
    @Test
    void indexaPorCabecalhoENaoReescreveOQueNaoMudou() throws Exception {
        KnowledgeBase.Indexed first = knowledge.reindex();

        assertThat(first.files()).isEqualTo(2);
        assertThat(first.chunks()).isGreaterThanOrEqualTo(4);
        assertThat(knowledge.status()).containsEntry("files", 2L).containsEntry("staleFiles", 0);

        assertThat(knowledge.reindex().chunks()).as("nada mudou, nada reescrito").isZero();

        Files.writeString(docs.resolve("model.md"), "# Modelo\n\nMudou tudo.\n");
        assertThat(knowledge.staleFiles()).isEqualTo(1);
        assertThat(knowledge.reindex().chunks()).isEqualTo(1);

        Files.delete(docs.resolve("model.md"));
        knowledge.reindex();
        assertThat(knowledge.status()).containsEntry("files", 1L);
    }

    @AcceptanceCriteria("SPEC-028/CA-2")
    @Test
    void buscaTrazArquivoSecaoETrechoDentroDoTeto() {
        knowledge.reindex();

        List<KnowledgeStore.Hit> hits = knowledge.search("auditoria append-only", 8);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().heading()).isEqualTo("model.md › Modelo de segurança › 7. Auditoria");
        assertThat(hits.getFirst().text()).contains("cadeia de hash");
        assertThat(hits).hasSizeLessThanOrEqualTo(KnowledgeBase.MAX_HITS);
        assertThat(hits.stream().mapToInt(hit -> hit.text().length()).sum())
                .isLessThanOrEqualTo(KnowledgeBase.MAX_CHARS + 1);

        String context = knowledge.answerContext("onde o segredo é mascarado", 8);
        assertThat(context).contains("› 8. Segredos").contains("docs/security/model.md").contains("Redactor");
        assertThat(knowledge.answerContext("xilofone quântico", 8)).isEqualTo("Nada na documentação sobre isso.");
    }

    @AcceptanceCriteria("SPEC-028/CA-3")
    @Test
    void indiceVelhoEhDitoNaRespostaENoStatus() throws Exception {
        knowledge.reindex();
        Files.writeString(docs.resolve("model.md"), Files.readString(docs.resolve("model.md")) + "\n\nMais texto.\n");

        assertThat(knowledge.status()).containsEntry("staleFiles", 1);
        assertThat(knowledge.answerContext("auditoria", 3)).startsWith("[índice velho: 1 arquivo(s)");
    }

    @AcceptanceCriteria("SPEC-028/CA-4")
    @Test
    void oAgenteRagSoLeEEhObrigadoACitarArquivoESecao() {
        AgentProfile rag = new AgentRegistry(home.resolve("agents")).find("rag").orElseThrow();

        // O prompt é o que obriga a citação; a ferramenta fixada é o que garante que
        // ele consulte antes de afirmar (SPEC-028 §3).
        assertThat(rag.prompt()).contains("cite a fonte em cada").contains("(arquivo › seção)");
        assertThat(rag.tools().pinned()).contains("rag.search");
        assertThat(rag.ceiling().wire()).isEqualTo("green");
        assertThat(List.of("fs.write", "fs.delete", "process.run", "memory.write", "change.plan"))
                .allSatisfy(tool -> assertThat(rag.tools().allows(tool))
                        .as("o agente de RAG só lê: %s", tool).isFalse());

        // E a citação que ele recebe traz mesmo arquivo e seção.
        knowledge.reindex();
        assertThat(knowledge.answerContext("auditoria append-only", 3))
                .contains("docs/security/model.md").contains("› 7. Auditoria");
    }

    @Test
    void semIndiceARespostaDizComoResolver() {
        assertThat(knowledge.answerContext("qualquer coisa", 3))
                .isEqualTo("Não há índice de documentação ainda. Reindexe pela tela (rag.reindex).");
    }

    @Test
    void oDivisorEntendeFrontMatterCabecalhoAninhadoEBlocoDeCodigo() {
        String text = """
                ---
                securityLevel: public
                ---

                # Topo

                Antes.

                ## Meio

                ```text
                # isto não é cabeçalho
                ```

                Depois.
                """;
        List<KnowledgeStore.Chunk> chunks = MarkdownChunker.chunks("arq.md", text);

        assertThat(MarkdownChunker.frontMatter(text)).containsEntry("securityLevel", "public");
        assertThat(chunks).extracting(KnowledgeStore.Chunk::heading)
                .containsExactly("arq.md › Topo", "arq.md › Topo › Meio");
        assertThat(chunks.getLast().text()).contains("# isto não é cabeçalho").contains("Depois.");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.level()).isEqualTo("public"));
    }
}
