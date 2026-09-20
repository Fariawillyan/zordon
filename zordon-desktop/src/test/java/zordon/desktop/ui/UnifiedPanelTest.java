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

import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;

/** Uma navegação só, e cada assunto numa casa só (SPEC-031, SPEC-032, SPEC-033). */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class UnifiedPanelTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    private static final ShellActions NO_ACTIONS = new ShellActions() {
        @Override
        public void send(String text, ComposerTarget target) {}

        @Override
        public void newConversation() {}

        @Override
        public void cancelTurn(String turnId) {}

        @Override
        public void refreshDiagnostics() {}

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
    };

    @AcceptanceCriteria("SPEC-031/CA-1")
    @Test
    void haUmaNavegacaoSoESemTiraDeAbas() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
                stage.show();
                shell.applyCss();
                shell.layout();

                // A tira de abas dos Ajustes não existe mais em nenhuma tela.
                for (Destination destination : Destination.values()) {
                    state.select(destination);
                    shell.applyCss();
                    shell.layout();
                    assertThat(shell.lookupAll(".settings-tab"))
                            .as("tira de abas em %s", destination).isEmpty();
                }
                // E a navegação é uma só: a coluna.
                assertThat(shell.lookupAll(".settings-navigation")).isEmpty();
                assertThat(shell.rail().item(Destination.VOICE)).isNotNull();
            } finally {
                shell.close();
                stage.close();
            }
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-031/CA-4")
    @Test
    void oQueEraAbaDeAjustesTemUmaCasaSo() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            state.memoryFacts().add(Map.of("id", "f_1", "kind", "PREFERENCE", "content", "café sem açúcar"));
            state.mcpServers().add(Map.of("name", "git", "state", "ready", "tools", List.of("git.status")));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
                stage.show();

                // Cada id aparece numa tela só: duas cópias seriam duas verdades.
                for (String id : List.of("#memory-list", "#mcp-list", "#automation-list",
                        "#task-list", "#lockdown-toggle", "#quarantine-list")) {
                    assertThat(shell.lookupAll(id)).as("%s aparece mais de uma vez", id).hasSizeLessThanOrEqualTo(1);
                }

                state.select(Destination.MEMORY);
                shell.applyCss();
                shell.layout();
                assertThat(visivel(shell, "#memory-list")).as("a memória tem casa").isTrue();

                state.select(Destination.SECURITY);
                shell.applyCss();
                shell.layout();
                assertThat(visivel(shell, "#lockdown-toggle")).as("o kill switch tem casa").isTrue();
            } finally {
                shell.close();
                stage.close();
            }
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-031/CA-5")
    @Test
    void naJanelaMinimaOConsoleCabeAoLadoDaColuna() throws Exception {
        onFx(() -> {
            ZordonShell shell = new ZordonShell(online(), NO_ACTIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, 720, 560));
                stage.show();
                shell.applyCss();
                shell.layout();

                assertThat(shell.rail().getWidth()).isEqualTo(NavigationPane.WIDTH);
                // A soma tem de caber: console transbordando é rolagem horizontal.
                assertThat(shell.rail().getWidth() + shell.voice().getWidth())
                        .as("coluna + console passam da janela")
                        .isLessThanOrEqualTo(720.5);
            } finally {
                shell.close();
                stage.close();
            }
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-032/CA-3")
    @Test
    void aFaixaMostraOsQuatroEstadosEmPalavras() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            state.voice(Map.of("mode", "wake", "effective", "wake", "activity", "idle",
                    "capture", Map.of("state", "on", "requested", true, "confirmedAt", "2026-09-20T10:00:00Z"),
                    "host", Map.of("connected", true, "device", "Microfone USB"),
                    "engine", Map.of("state", "ready")));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            VoiceStatusStrip faixa = shell.voice().statusStrip();

            assertThat(faixa.value(0)).as("microfone").isEqualTo("Microfone USB");
            assertThat(faixa.value(1)).as("motor de voz").isEqualTo("Pronto");
            assertThat(faixa.value(2)).as("modelo").contains("local");
            assertThat(faixa.value(3)).as("memória").isEqualTo("Ativa");
            // Sem host, a palavra muda — não só a cor.
            state.voice(Map.of("mode", "wake", "effective", "unavailable", "activity", "idle",
                    "capture", Map.of("state", "off", "requested", false, "confirmedAt", "2026-09-20T10:00:00Z"),
                    "host", Map.of("connected", false),
                    "engine", Map.of("state", "absent", "reason", "motor de voz não instalado")));
            assertThat(faixa.value(0)).isEqualTo("Não conectado");
            assertThat(faixa.value(1)).isEqualTo("motor de voz não instalado");
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-032/CA-4")
    @Test
    void osAjustesDeVozSaoUmDestinoProprio() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);

            assertThat(Destination.SETTINGS.label()).isEqualTo("Ajustes");
            assertThat(Destination.SETTINGS.group()).isEqualTo(Destination.Group.OPERATION);
            assertThat(state.select(Destination.SETTINGS)).isTrue();
            assertThat(visivel(shell, ".settings-page")).as("a tela de ajustes abre pelo destino").isTrue();
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-033/CA-3")
    @Test
    void oDestinoAjustesUsaOMesmoSeletorDeModo() throws Exception {
        onFx(() -> {
            DesktopState state = online();
            state.voice(Map.of("mode", "wake", "effective", "wake", "activity", "idle",
                    "capture", Map.of("state", "on", "requested", true, "confirmedAt", "2026-09-20T10:00:00Z"),
                    "host", Map.of("connected", true),
                    "engine", Map.of("state", "ready")));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            state.select(Destination.SETTINGS);

            // Duas instâncias do MESMO componente — não duas listas de modos.
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
                stage.show();
                shell.applyCss();
                shell.layout();
                assertThat(shell.lookupAll(".voice-mode-picker"))
                        .hasSize(2)
                        .allSatisfy(node -> assertThat(node).isInstanceOf(VoiceModePicker.class));
                assertThat(shell.lookup("#voice-mode-picker-settings")).isNotNull();
                assertThat(shell.lookup("#voice-mode-picker-console")).isNotNull();
            } finally {
                stage.close();
            }
            shell.close();
            return null;
        });
    }

    private static boolean visivel(ZordonShell shell, String seletor) {
        Node node = shell.lookup(seletor);
        if (node == null) {
            return false;
        }
        for (Node atual = node; atual != null; atual = atual.getParent()) {
            if (!atual.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static DesktopState online() {
        DesktopState state = new DesktopState();
        state.online("0.1.0");
        state.diagnostics(Map.of(
                "core", Map.of("version", "0.1.0", "uptimeSeconds", 180),
                "providers", Map.of("local", "configurado (local)"),
                "roles", Map.of("conversation", Map.of("provider", "local", "model", "gpt-local", "ready", true))));
        return state;
    }
}
