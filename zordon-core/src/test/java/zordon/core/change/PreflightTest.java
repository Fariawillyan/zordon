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
package zordon.core.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;
import zordon.core.chat.Intent;
import zordon.core.chat.IntentRouter;
import zordon.core.event.ZordonEventBus;
import zordon.core.notify.NotificationCenter;
import zordon.core.notify.ZordonMessage;
import zordon.core.rag.KnowledgeBase;
import zordon.core.usage.UsageTracker;
import zordon.memory.SqliteMemoryStore;
import zordon.memory.ZordonDatabase;
import zordon.memory.TaskStore;

/** O preflight de nove passos e a conta de tokens (SPEC-029). */
class PreflightTest {

    @TempDir
    Path home;

    private final List<ZordonMessage> notices = new CopyOnWriteArrayList<>();
    private ZordonDatabase db;
    private SqliteMemoryStore store;
    private NotificationCenter notifications;
    private KnowledgeBase knowledge;
    private Preflight preflight;

    @BeforeEach
    void setUp() throws Exception {
        db = new ZordonDatabase(home.resolve("zordon.db"), Clock.systemUTC());
        store = db.memory();
        notifications = new NotificationCenter(home.resolve("notifications.db"), Clock.systemUTC(), notices::add);
        Path docs = home.resolve("docs/specs/mcp");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("SPEC-020-cliente-mcp.md"), """
                # SPEC-020 — Cliente MCP

                ## 3. Escopo

                O servidor MCP entra por configuração, e a tela de MCP mostra o estado de cada um.
                """);
        Files.createDirectories(home.resolve("docs/security"));
        Files.writeString(home.resolve("docs/security/model.md"), """
                # Modelo de segurança

                ## 5. Motor de permissão

                O motor de permissão e a auditoria formam o núcleo de confiança.
                """);
        knowledge = new KnowledgeBase(db.knowledge(), List.of(home.resolve("docs")));
        knowledge.reindex();
        preflight = new Preflight(db.tasks(), knowledge, new AgentRegistry(home.resolve("agents")), notifications);
    }

    @AfterEach
    void tearDown() {
        notifications.close();
        db.close();
    }

    @AcceptanceCriteria("SPEC-029/CA-1")
    @Test
    void osNovePassosSaoRegistradosEONadaEhExecutado() {
        assertThat(new IntentRouter().route("Zordon, planeje a mudança: adicionar uma tela para MCP"))
                .isEqualTo(new Intent.Tool("change.plan", Map.of("goal", "adicionar uma tela para MCP"),
                        "planejar-mudanca"));

        Preflight.Plan plan = preflight.plan("adicionar uma tela para MCP");

        assertThat(plan.steps()).hasSize(9);
        assertThat(plan.steps()).extracting(Preflight.Step::id)
                .containsExactly("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9");
        assertThat(plan.steps().get(1).finding()).as("o passo 2 cita a documentação").contains("SPEC-020");
        assertThat(plan.steps().get(2).finding()).contains("SPEC-020-cliente-mcp.md");
        assertThat(plan.steps().get(3).finding()).startsWith("sim:");
        assertThat(plan.touchesTrustCore()).isFalse();
        assertThat(plan.steps().get(5).finding()).contains("codereview");
        assertThat(plan.tokenBudget()).isEqualTo(Preflight.DEFAULT_BUDGET);

        TaskStore.TaskView task = db.tasks().task(plan.taskId()).orElseThrow();
        assertThat(task.state()).isEqualTo("waiting_human");
        assertThat(task.origin()).isEqualTo("change:preflight");
        assertThat(task.steps()).hasSize(9).allSatisfy(step -> assertThat(step.state()).isEqualTo("done"));
        assertThat(notices).singleElement().satisfies(message -> {
            assertThat(message.title()).contains("Plano de mudança pronto");
            assertThat(message.actionTaken()).isEqualTo("Nada foi alterado no projeto.");
        });
        assertThat(Preflight.summary(plan)).contains("esperando você");
    }

    @AcceptanceCriteria("SPEC-029/CA-2")
    @Test
    void mudancaNoNucleoDeConfiancaVemMarcada() {
        Preflight.Plan plan = preflight.plan("mudar o motor de permissão para aceitar YELLOW em automação");

        assertThat(plan.touchesTrustCore()).isTrue();
        assertThat(plan.steps().get(4).finding()).contains("NÚCLEO DE CONFIANÇA").contains("PR humano");
        assertThat(plan.steps().get(8).finding()).contains("nunca é aplicada sozinha");
        assertThat(notices.getLast().severity().wire()).isEqualTo("high");
        assertThat(Preflight.summary(plan)).contains("PR humano");
    }

    @AcceptanceCriteria("SPEC-029/CA-3")
    @Test
    void usoDeTokensSomaPorDiaEAtorESobreviveAoReinicio() {
        ZordonEventBus bus = new ZordonEventBus("01TESTE00000000000000000000");
        try (UsageTracker tracker = new UsageTracker(db.usage(), bus, Clock.systemUTC())) {
            tracker.accept(new EventEnvelope(1, Clock.systemUTC().instant(), EventType.AI_RESPONSE,
                    Map.of("done", true, "provider", "claude", "model", "sonnet",
                            "usage", Map.of("inputTokens", 1000, "outputTokens", 200))));
            tracker.accept(new EventEnvelope(2, Clock.systemUTC().instant(), EventType.AI_RESPONSE,
                    Map.of("done", false, "usage", Map.of("inputTokens", 5000, "outputTokens", 0))));
            tracker.accept(new EventEnvelope(3, Clock.systemUTC().instant(), EventType.AGENT_FINISHED,
                    Map.of("agent", "research", "usage", Map.of("tokens", 700))));

            Map<String, Object> summary = tracker.summary(7);
            assertThat(summary).containsEntry("inputTokens", 1700L).containsEntry("outputTokens", 200L)
                    .containsEntry("calls", 2L);
            @SuppressWarnings("unchecked")
            Map<String, Object> byActor = (Map<String, Object>) summary.get("byActor");
            assertThat(byActor).containsEntry("turno", 1200L).containsEntry("agente:research", 700L);
            @SuppressWarnings("unchecked")
            Map<String, Object> byDay = (Map<String, Object>) summary.get("byDay");
            assertThat(byDay).containsKey(LocalDate.now(Clock.systemDefaultZone()).toString());
        }
        bus.close();

        db.close();
        db = new ZordonDatabase(home.resolve("zordon.db"), Clock.systemUTC());
        store = db.memory();
        assertThat(db.usage().usageSummary(7)).containsEntry("inputTokens", 1700L);
    }

    @AcceptanceCriteria("SPEC-029/CA-5")
    @Test
    void agentesDeEngenhariaExistemComTetoEComRagFixado() {
        AgentRegistry registry = new AgentRegistry(home.resolve("agents"));

        assertThat(registry.list()).extracting(AgentProfile::id)
                .contains("spec", "architecture", "java", "testing", "codereview", "documentation", "rag");
        assertThat(registry.find("spec").orElseThrow().ceiling().wire()).isEqualTo("green");
        assertThat(registry.find("java").orElseThrow().ceiling().wire()).isEqualTo("yellow");
        assertThat(registry.list()).filteredOn(agent -> List.of("spec", "architecture", "codereview", "rag")
                        .contains(agent.id()))
                .allSatisfy(agent -> assertThat(agent.tools().pinned()).contains("rag.search"));
        assertThat(registry.find("rag").orElseThrow().tools().allows("fs.write"))
                .as("o agente de RAG só lê").isFalse();
    }
}
