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
package zordon.desktop.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static zordon.desktop.ui.FxTestSupport.onFx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;

/** Cada destino abre a sua tela, com o que o núcleo já sabe (SPEC-030). */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class DestinationPagesTest {

    /** Os nove que deixaram de ser promessa, e o id da tela de cada um. */
    private static final Map<Destination, String> PAGES = Map.of(
            Destination.AGENTS, "#agents-view",
            Destination.MCP, "#mcp-view",
            Destination.SKILLS, "#skills-view",
            Destination.AUTOMATIONS, "#automations-view",
            Destination.MEMORY, "#memory-view",
            Destination.KNOWLEDGE, "#knowledge-view",
            Destination.SYSTEM, "#system-view",
            Destination.USAGE, "#usage-view",
            Destination.SECURITY, "#security-view");

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    /** Conta o que cada tela pediu ao núcleo, sem responder nada. */
    private static class Loads extends FakeShellActions {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void send(String text, ComposerTarget target) {}

        @Override
        public void newConversation() {}

        @Override
        public void cancelTurn(String turnId) {}

        @Override
        public void refreshDiagnostics() {
            calls.add("diagnostics");
        }

        @Override
        public void setVoiceMode(String mode) {}

        @Override
        public void loadVoiceDevices() {}

        @Override
        public void selectVoiceDevice(String deviceId) {}

        @Override
        public void testMicrophone() {}

        @Override
        public void startListening() {}

        @Override
        public void stopListening() {}

        @Override
        public void loadAgents() {
            calls.add("agents");
        }

        @Override
        public void loadMcp() {
            calls.add("mcp");
        }

        @Override
        public void loadSkills() {
            calls.add("skills");
        }

        @Override
        public void loadAutomations() {
            calls.add("automations");
        }

        @Override
        public void loadMemory() {
            calls.add("memory");
        }

        @Override
        public void loadKnowledge() {
            calls.add("knowledge");
        }

        @Override
        public void loadUsage() {
            calls.add("usage");
        }

        @Override
        public void loadSystem() {
            calls.add("system");
        }

        @Override
        public void loadFindings() {
            calls.add("findings");
        }

        @Override
        public void loadSecurityEvents() {
            calls.add("securityEvents");
        }
    }

    @AcceptanceCriteria("SPEC-030/CA-1")
    @Test
    void nenhumDestinoImplementadoAparecComoPromessa() {
        for (Destination destination : PAGES.keySet()) {
            assertThat(destination.isAvailable()).as("%s deveria abrir", destination).isTrue();
            assertThat(destination.badge()).as("%s ainda tem selo de marco", destination).isEmpty();
            assertThat(destination.unavailableReason()).isEmpty();
        }
    }

    @AcceptanceCriteria("SPEC-030/CA-2")
    @ParameterizedTest(name = "{0}")
    @CsvSource({"AGENTS, #agents-view", "MCP, #mcp-view", "SKILLS, #skills-view",
            "AUTOMATIONS, #automations-view", "MEMORY, #memory-view", "KNOWLEDGE, #knowledge-view",
            "SYSTEM, #system-view", "USAGE, #usage-view", "SECURITY, #security-view"})
    void cadaDestinoAbreASuaTela(String name, String id) throws Exception {
        onFx(() -> {
            Destination destination = Destination.valueOf(name);
            DesktopState state = online();
            ZordonShell shell = new ZordonShell(state, new Loads());

            assertThat(state.select(destination)).as("o destino recusou abrir").isTrue();

            Node page = shell.lookup(id);
            assertThat(page).as("a tela de %s não existe", name).isNotNull();
            assertThat(page.isVisible()).as("a tela de %s não ficou visível", name).isTrue();
            // E só ela: duas telas visíveis seria o shell mostrando duas coisas.
            PAGES.values().stream().filter(other -> !other.equals(id)).forEach(other ->
                    assertThat(shell.lookup(other).isVisible()).as("%s ficou visível junto", other).isFalse());
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-030/CA-3")
    @Test
    void aCargaSaiUmaVezAoAbrirEDeNovoSoNoAtualizar() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            Loads loads = new Loads();
            ZordonShell shell = new ZordonShell(state, loads);

            // Montar o shell não pode falar com o núcleo: nove telas, nenhuma chamada.
            assertThat(loads.calls).isEmpty();

            state.select(Destination.SKILLS);
            assertThat(loads.calls).containsExactly("skills");

            state.select(Destination.USAGE);
            state.select(Destination.SKILLS);
            assertThat(loads.calls).as("voltar não repete a carga").containsExactly("skills", "usage");

            ((javafx.scene.control.Button) inside(shell, "#skills-view", "#refresh-skills")).fire();
            assertThat(loads.calls).containsExactly("skills", "usage", "skills");
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-030/CA-4")
    @Test
    void listaVaziaViraFraseENaoEspacoEmBranco() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            ZordonShell shell = new ZordonShell(state, new Loads());
            state.select(Destination.AGENTS);

            javafx.scene.layout.VBox empty = (javafx.scene.layout.VBox) inside(shell, "#agents-view", "#agent-list");
            assertThat(empty.getChildren()).singleElement()
                    .satisfies(node -> assertThat(((javafx.scene.control.Label) node).getText())
                            .contains("Nenhum agente"));

            state.resources().agents().add(Map.of("id", "research", "description", "Pesquisa", "ceiling", "green",
                    "role", "agent_light", "source", "builtin"));
            // O render troca os nós, então a lista de antes não serve mais.
            javafx.scene.layout.VBox list = (javafx.scene.layout.VBox) inside(shell, "#agents-view", "#agent-list");
            assertThat(list.getChildren()).hasSize(1);
            assertThat(((javafx.scene.control.Label) list.getChildren().getFirst()).getText())
                    .contains("research").contains("verde");
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-030/CA-5")
    @Test
    void semRespostaDoNucleoATelaAbreEDizOQueFalta() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            AtomicInteger falhas = new AtomicInteger();
            ShellActions quebrado = new Loads() {
                @Override
                public void loadKnowledge() {
                    falhas.incrementAndGet();
                    throw new IllegalStateException("núcleo fora do ar");
                }
            };
            ZordonShell shell = new ZordonShell(state, quebrado);

            // A tela abre mesmo assim: o shell não pode cair porque o núcleo caiu.
            assertThat(catching(() -> state.select(Destination.KNOWLEDGE))).isNull();
            assertThat(falhas.get()).isEqualTo(1);
            assertThat(shell.lookup("#knowledge-view").isVisible()).isTrue();
            shell.close();
            return null;
        });
    }

    /**
     * Um nó de dentro de uma tela. O conteúdo de um {@code ScrollPane} só entra no
     * grafo quando o skin existe, então a busca começa no {@code getContent()}.
     */
    private static Node inside(ZordonShell shell, String page, String id) {
        javafx.scene.control.ScrollPane pane = (javafx.scene.control.ScrollPane) shell.lookup(page);
        return pane.getContent().lookup(id);
    }

    /** O que a chamada lançou, ou {@code null} se ela passou. */
    private static Throwable catching(Runnable work) {
        try {
            work.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static DesktopState online() {
        DesktopState state = new DesktopState();
        state.online("0.1.0");
        return state;
    }
}
