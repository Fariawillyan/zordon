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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;

/**
 * O shell montado de verdade, nas quatro faixas de largura. Precisa de um
 * display (WSLg local, xvfb na CI); as imagens vão para
 * {@code build/ui-snapshots} para revisão contra a prévia.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class ShellLayoutTest {

    private static final Path SNAPSHOTS = Path.of("build", "ui-snapshots");

    private static final ShellActions NO_ACTIONS = new FakeShellActions() {
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

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    @AcceptanceCriteria("SPEC-010/CA-1")
    @Test
    void aJanelaTemSoAColunaEATelaEAbreNaVoz() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
            stage.show();
            shell.applyCss();
            shell.layout();

            assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.VOICE);
            assertThat(shell.lookupAll(".app-header")).isEmpty();
            assertThat(shell.lookupAll(".status-bar")).isEmpty();
            // A única caixa de texto é a da Conversa, que não está visível na Voz.
            assertThat(shell.lookupAll(".text-area")).allSatisfy(node -> assertThat(node.isVisible()
                    && node.getParent().isVisible() && isShowing(node)).isFalse());
            assertThat(shell.rail().lookupAll(".nav-item")).hasSize(zordon.desktop.shell.Destination.values().length);
            shell.close();
            stage.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-010/CA-8")
    @ParameterizedTest(name = "{0}×{1}")
    @CsvSource({"960, 720", "720, 560"})
    void oConsoleCabeSemRolagemEAColunaMantemALargura(int width, int height) throws Exception {
        onFx(() -> {
            ZordonShell shell = new ZordonShell(onlineWithConversation(), NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, width, height));
            stage.show();
            shell.applyCss();
            shell.layout();
            assertThat(shell.rail().getWidth()).isEqualTo(NavigationPane.WIDTH);
            assertThat(shell.voice().getWidth()).isLessThanOrEqualTo(width - NavigationPane.WIDTH + 0.5);
            assertThat(shell.voice().getBoundsInParent().getMaxX()).isLessThanOrEqualTo(width - NavigationPane.WIDTH + 0.5);
            shell.close();
            stage.close();
            return null;
        });
    }

    @ParameterizedTest(name = "aviso compacto {0}×{1}")
    @CsvSource({"960, 720", "720, 560"})
    void notificacaoFicaCompactaComTextoLongoEAbreOsAvisos(int width, int height) throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            state.security().notification(Map.of("messageId", "first", "severity", "warning",
                    "title", "43621: host.new-listener — " + "detalhe longo ".repeat(30),
                    "actionTaken", "O que a defesa fez está no histórico da tela de Segurança. ".repeat(20)));
            state.security().notification(Map.of("messageId", "second", "severity", "high",
                    "title", "Outro aviso", "actionTaken", "Aguardando sua leitura."));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, width, height));
                stage.show();
                shell.applyCss();
                shell.layout();
                NotificationBar bar = (NotificationBar) shell.lookup("#notification-bar");
                assertThat(bar.getHeight()).isPositive().isLessThan(190);
                assertThat(bar.getWidth()).isLessThanOrEqualTo(460);
                javafx.scene.control.Button ack = (javafx.scene.control.Button) bar.lookup("#notification-ack");
                assertThat(ack.getWidth()).isGreaterThanOrEqualTo(ack.prefWidth(-1));
                assertThat(bar.localToScene(bar.getBoundsInLocal()).getMaxX()).isLessThanOrEqualTo(width);
                assertThat(((javafx.scene.control.Label) bar.lookup(".notification-count")).getText())
                        .isEqualTo("+1 pendente");
                FxTestSupport.save(stage.getScene(), SNAPSHOTS.resolve("aviso-compacto-" + width + ".png"));
                ((javafx.scene.control.Button) bar.lookup("#notification-details")).fire();
                shell.layout();
                assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.SECURITY);
                assertThat(isShowing(shell.lookup("#notification-list"))).isTrue();
                assertThat(state.security().notifications()).hasSize(2);
                assertThat(bar.isVisible()).isFalse();
                ((javafx.scene.control.Button) shell.lookup("#notification-read-first")).fire();
                assertThat(state.security().notifications()).hasSize(1);
                state.select(zordon.desktop.shell.Destination.VOICE);
                assertThat(bar.isVisible()).isTrue();
                assertThat(bar.text()).contains("Outro aviso");
                ack.fire();
                assertThat(bar.isManaged()).isFalse();
            } finally {
                shell.close();
                stage.close();
            }
            return null;
        });
    }

    @ParameterizedTest(name = "ajustes compactos {0}×{1}")
    @CsvSource({"960, 720", "720, 560"})
    void categoriasLimitamAAlturaEAsAcoesCabem(int width, int height) throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            for (int i = 0; i < 30; i++) {
                state.security().findings().add(Map.of("findingId", "f" + i, "severity", "warning",
                        "title", (43621 + i) + ": host.new-listener",
                        "rationale", "host.new-listener. Peso somado 0.40 na janela de 60 s."));
            }
            state.resources().mcpServers().add(Map.of("name", "Servidor de desenvolvimento com nome longo",
                    "state", "drift", "tools", List.of(), "drift", true));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            state.select(zordon.desktop.shell.Destination.AUTOMATIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, width, height));
                stage.show();
                shell.applyCss();
                shell.layout();
                assertThat(isShowing(shell.lookup("#automation-list"))).isTrue();
                assertThat(isShowing(shell.lookup("#finding-list"))).isFalse();
                FxTestSupport.save(stage.getScene(), SNAPSHOTS.resolve("aplicativos-" + width + ".png"));
                state.select(zordon.desktop.shell.Destination.SECURITY);
                shell.applyCss();
                shell.layout();
                javafx.scene.control.ScrollPane list = settingsList(shell.lookup("#finding-list"));
                assertThat(settingsList(shell.lookup("#notification-list")).getHeight()).isLessThan(50);
                assertThat(list.getHeight()).isLessThanOrEqualTo(260);
                javafx.scene.control.Button ack = (javafx.scene.control.Button) shell.lookup("#finding-ack-f0");
                assertThat(ack.getWidth()).isGreaterThanOrEqualTo(ack.prefWidth(-1));
                FxTestSupport.save(stage.getScene(), SNAPSHOTS.resolve("seguranca-" + width + ".png"));
            } finally {
                shell.close();
                stage.close();
            }
            return null;
        });
    }

    private static javafx.scene.control.ScrollPane settingsList(javafx.scene.Node content) {
        javafx.scene.Parent parent = content.getParent();
        while (!(parent instanceof javafx.scene.control.ScrollPane)) parent = parent.getParent();
        return (javafx.scene.control.ScrollPane) parent;
    }

    @Test
    void botaoDoRodapeMostraStatusAoVivoEAbreDiagnostico() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, 720, 560));
                stage.show();
                shell.applyCss();
                shell.layout();
                var button = (javafx.scene.control.Button) shell.lookup("#core-status-button");
                assertThat(button.isFocusTraversable()).isTrue();
                button.fire();
                var menu = button.getContextMenu();
                assertThat(menu.isShowing()).isTrue();
                var info = (javafx.scene.control.CustomMenuItem) menu.getItems().getFirst();
                var connection = (javafx.scene.control.Label) info.getContent().lookup("#core-status-connection");
                assertThat(connection.getText()).contains("conectado");
                state.offline("Conexão encerrada");
                assertThat(connection.getText()).contains("offline");
                assertThat(button.getAccessibleText()).contains("offline");
                menu.hide();
                button.fire();
                assertThat(menu.isShowing()).isTrue();
                menu.getItems().getLast().fire();
                assertThat(menu.isShowing()).isFalse();
                assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.DIAGNOSTICS);
            } finally {
                shell.close();
                stage.close();
            }
            return null;
        });
    }

    /** Visível de fato: o nó e todos os ancestrais visíveis. */
    private static boolean isShowing(javafx.scene.Node node) {
        for (javafx.scene.Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @AcceptanceCriteria("SPEC-005/CA-8")
    @Test
    void duasMilMensagensCriamSoOsNosVisiveis() throws Exception {
        int rendered = onFx(() -> {
            ZordonShell shell = new ZordonShell(onlineWithConversation(), NO_ACTIONS);
            for (int i = 0; i < 1000; i++) {
                shell.chatUser("pergunta " + i);
                shell.chatAssistant("resposta " + i + " com algum texto para ocupar a linha.", "");
            }
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, 1600, 900));
            shell.chat().setVisible(true);
            stage.show();
            shell.applyCss();
            shell.layout();
            assertThat(shell.chat().size()).isEqualTo(2000);
            int cells = shell.chat().renderedCells();
            stage.close();
            return cells;
        });

        assertThat(rendered).isPositive().isLessThan(60);
    }

    @AcceptanceCriteria("SPEC-006/CA-13")
    @Test
    void osAjustesMostramOEstadoConfirmadoEAPilulaOfereceDesligar() throws Exception {
        VoiceView voiceView = onFx(() -> {
            DesktopState state = onlineWithConversation();
            state.voice().apply(Map.of(
                    "mode", "wake",
                    "effective", "wake",
                    "activity", "idle",
                    "capture", Map.of("state", "on", "requested", true, "confirmedAt", "2026-09-18T17:02:11Z"),
                    "host", Map.of("connected", true, "device", "Microfone USB"),
                    "engine", Map.of("state", "ready")));
            state.accept(new zordon.api.event.EventEnvelope(9, java.time.Instant.now(),
                    zordon.api.event.EventType.VOICE_STOPPED,
                    Map.of("text", "quais containers estão rodando", "confidence", 0.94)));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            state.select(zordon.desktop.shell.Destination.VOICE);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, 1600, 900));
            stage.show();
            shell.applyCss();
            shell.layout();
            FxTestSupport.save(shell.getScene(), SNAPSHOTS.resolve("voz-1600.png"));
            stage.close();
            return shell.voice();
        });

        assertThat(voiceView.pillVisible()).isTrue();
        assertThat(voiceView.pillText()).isEqualTo("Aguardando “Zordon”");
        assertThat(voiceView.lookup("#voice-turn-off").isVisible()).isTrue();
    }

    @AcceptanceCriteria("SPEC-009/CA-8")
    @Test
    void oCartaoDeTesteMostraOMedidorAoVivoDuranteOTeste() throws Exception {
        double progress = onFx(() -> {
            DesktopState state = onlineWithConversation();
            state.voice().apply(Map.of(
                    "mode", "off",
                    "effective", "off",
                    "activity", "idle",
                    "capture", Map.of("state", "on", "requested", true, "confirmedAt", "2026-09-18T17:02:11Z"),
                    "host", Map.of("connected", true),
                    "engine", Map.of("state", "absent", "reason", "motor de voz não instalado"),
                    "test", Map.of("until", "2099-01-01T00:00:00Z"),
                    "lastTest", Map.of("verdict", "low", "message", "sinal baixo: aproxime-se ou aumente o ganho",
                            "peakDbfs", -38.2, "averageDbfs", -51.0)));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            state.select(zordon.desktop.shell.Destination.VOICE);
            state.accept(new zordon.api.event.EventEnvelope(10, java.time.Instant.now(),
                    zordon.api.event.EventType.VOICE_LEVEL, Map.of("rms", -24.0, "peak", -9.5)));
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
            stage.show();
            shell.applyCss();
            shell.layout();
            FxTestSupport.save(shell.getScene(), SNAPSHOTS.resolve("ajustes-teste-microfone-960.png"));
            javafx.scene.control.ProgressBar meter =
                    (javafx.scene.control.ProgressBar) shell.lookup("#microphone-meter");
            javafx.scene.control.Button start = (javafx.scene.control.Button) shell.lookup("#microphone-test");
            assertThat(start.isDisabled()).isTrue();
            assertThat(shell.voice().pillText()).isEqualTo("Testando microfone…");
            double value = meter.isVisible() ? meter.getProgress() : -1;
            stage.close();
            return value;
        });

        assertThat(progress).isEqualTo(0.6);
    }

    @AcceptanceCriteria("SPEC-010/CA-2")
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}×{1}")
    @org.junit.jupiter.params.provider.CsvSource({"960, 720", "720, 560", "1366, 768"})
    void aVozEmRepousoEOConsoleDaReferencia(int width, int height) throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            state.voice().apply(Map.of(
                    "mode", "off", "effective", "off", "activity", "idle",
                    "capture", Map.of("state", "off", "requested", false, "confirmedAt", "2026-09-18T17:02:11Z"),
                    "host", Map.of("connected", true),
                    "engine", Map.of("state", "absent", "reason", "motor de voz não instalado")));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, width, height));
            stage.show();
            shell.applyCss();
            shell.layout();
            FxTestSupport.save(shell.getScene(), SNAPSHOTS.resolve("voz-repouso-" + width + ".png"));
            assertThat(shell.voice().pillVisible()).isFalse();
            assertThat(shell.rail().getWidth()).isEqualTo(NavigationPane.WIDTH);
            shell.close();
            stage.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-010/CA-4")
    @Test
    void oIndicadorDoNucleoMudaDeCorEDizODetalhe() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            javafx.scene.Node core = shell.rail().coreIndicator();

            assertThat(core.getStyleClass()).contains("core-online");
            assertThat(shell.rail().coreText()).startsWith("Núcleo conectado");

            state.offline("conexão encerrada");

            assertThat(core.getStyleClass()).contains("core-offline").doesNotContain("core-online");
            assertThat(shell.rail().coreText()).contains("offline").contains("conexão encerrada");
            assertThat(shell.voice().pillVisible()).isTrue();
            assertThat(shell.voice().pillText()).isEqualTo("Núcleo offline — reconectando");
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-032/CA-2")
    @Test
    void aColunaLevaDiretoAQualquerDestinoSemPainel() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);

            // O Painel era um atalho para o que a coluna já mostra; ele saiu.
            assertThat(zordon.desktop.shell.Destination.values())
                    .noneSatisfy(destination -> assertThat(destination.label()).isEqualTo("Painel"));
            assertThat(zordon.desktop.shell.Destination.inGroup(
                    zordon.desktop.shell.Destination.Group.WORK))
                    .containsExactly(zordon.desktop.shell.Destination.VOICE,
                            zordon.desktop.shell.Destination.CHAT);

            shell.rail().item(zordon.desktop.shell.Destination.LOGS).fire();
            assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.LOGS);
            shell.rail().item(zordon.desktop.shell.Destination.MCP).fire();
            assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.MCP);
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-010/CA-6")
    @Test
    void aConversaTemOComposerNoPe() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
            stage.show();
            state.select(zordon.desktop.shell.Destination.CHAT);
            shell.applyCss();
            shell.layout();

            javafx.scene.Node conversation = shell.lookup(".conversation");
            javafx.scene.Node field = conversation.lookup(".text-area");
            assertThat(conversation.isVisible()).isTrue();
            assertThat(isShowing(field)).isTrue();
            // O composer é o último filho da Conversa: fica no pé.
            assertThat(((javafx.scene.layout.VBox) conversation).getChildren().getLast().lookup(".text-area"))
                    .isSameAs(field);
            FxTestSupport.save(shell.getScene(), SNAPSHOTS.resolve("conversa-960.png"));
            shell.close();
            stage.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-010/CA-7")
    @Test
    void osAjustesMostramModosMicrofoneTesteEMotor() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            state.voice().apply(Map.of(
                    "mode", "wake", "effective", "unavailable", "reason", "motor de voz não instalado",
                    "activity", "idle",
                    "capture", Map.of("state", "off", "requested", false, "confirmedAt", "2026-09-18T17:02:11Z"),
                    "host", Map.of("connected", true, "device", "Microfone (Logi USB Headset)"),
                    "engine", Map.of("state", "absent", "reason", "motor de voz não instalado")));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
            stage.show();
            state.select(zordon.desktop.shell.Destination.SETTINGS);
            shell.applyCss();
            shell.layout();
            FxTestSupport.save(shell.getScene(), SNAPSHOTS.resolve("ajustes-960.png"));

            var texts = shell.lookupAll(".label").stream()
                    .filter(node -> isShowing(node))
                    .map(node -> ((javafx.scene.control.Label) node).getText()).toList();
            assertThat(texts).contains("Pedido", "Em vigor", "Teste do microfone", "Motor de voz")
                    .anyMatch(text -> text.contains("Microfone desligado — confirmado pelo host"))
                    .anyMatch(text -> text.contains("Logi USB Headset"));
            assertThat(shell.lookup("#microphone-test")).isNotNull();
            shell.close();
            stage.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-012/CA-6")
    @Test
    void cadaEstadoTemAnimacaoPropriaSemTextoEMovimentoReduzidoSoMudaACor() throws Exception {
        onFx(() -> {
            VoiceVisualizer visualizer = new VoiceVisualizer(new zordon.desktop.audio.SoundPlayer(() -> {
                throw new IllegalStateException("sem saída nos testes");
            }));
            Stage stage = new Stage();
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(visualizer), 500, 330));
            stage.show();
            java.util.Map<String, Integer> frames = new java.util.LinkedHashMap<>();
            for (String state : List.of("idle", "listening", "understanding", "planning", "executing", "agents",
                    "speaking", "done", "attention", "error")) {
                visualizer.activity(state);
                Thread.sleep(260);
                visualizer.activity(state);
                frames.put(state, java.util.Arrays.hashCode(pixels(visualizer)));
                FxTestSupport.save(stage.getScene(), SNAPSHOTS.resolve("estado-" + state + ".png"));
            }
            // Dez estados, dez imagens diferentes.
            assertThat(new java.util.HashSet<>(frames.values())).hasSize(frames.size());

            visualizer.reducedMotion(true);
            visualizer.activity("executing");
            int first = java.util.Arrays.hashCode(pixels(visualizer));
            Thread.sleep(300);
            visualizer.activity("executing");
            assertThat(java.util.Arrays.hashCode(pixels(visualizer))).as("movimento reduzido não anima").isEqualTo(first);
            visualizer.activity("error");
            assertThat(java.util.Arrays.hashCode(pixels(visualizer))).as("a cor do erro continua").isNotEqualTo(first);
            stage.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-012/CA-7")
    @Test
    void aPilulaNaoTemTextoVisivelSoIconesComRotuloAcessivel() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            state.voice().apply(Map.of("mode", "wake", "effective", "wake", "activity", "idle",
                    "capture", Map.of("state", "on", "requested", true, "confirmedAt", "2026-09-18T17:02:11Z"),
                    "host", Map.of("connected", true), "engine", Map.of("state", "ready")));
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);

            assertThat(shell.voice().pillVisible()).isTrue();
            assertThat(shell.voice().pillVisibleTexts()).isEmpty();
            assertThat(shell.voice().pillText()).isEqualTo("Aguardando “Zordon”");
            javafx.scene.control.Button off = (javafx.scene.control.Button) shell.voice().lookup("#voice-turn-off");
            assertThat(off.getText()).isEmpty();
            assertThat(off.getAccessibleText()).isEqualTo("Desligar o microfone");
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-031/CA-2")
    @Test
    void todaANavegacaoEstaVisivelSemModoTecnico() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);

            // O modo técnico deixou de existir: Painel, Logs e Diagnóstico abrem
            // como qualquer outro destino (SPEC-031 supera SPEC-012 CA-8).
            for (zordon.desktop.shell.Destination destination : zordon.desktop.shell.Destination.values()) {
                javafx.scene.Node item = shell.rail().item(destination);
                assertThat(item).as("%s não está na coluna", destination).isNotNull();
                assertThat(item.isVisible()).as("%s escondido", destination).isTrue();
                assertThat(state.select(destination)).as("%s não abriu", destination).isTrue();
            }
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-031/CA-3")
    @Test
    void osMetadadosDaRespostaAparecemSempre() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
            stage.show();
            state.select(zordon.desktop.shell.Destination.CHAT);
            shell.chatAssistant("São 15h40.", "local · 12 ms · 0 tokens");
            shell.applyCss();
            shell.layout();

            assertThat(visibleMeta(shell)).contains("local · 12 ms · 0 tokens");
            shell.close();
            stage.close();
            return null;
        });
    }

    private static List<String> visibleMeta(ZordonShell shell) {
        return shell.lookupAll(".message-meta").stream().filter(javafx.scene.Node::isVisible)
                .map(node -> ((javafx.scene.control.Label) node).getText()).toList();
    }

    private static int[] pixels(VoiceVisualizer visualizer) {
        javafx.scene.image.WritableImage image = visualizer.snapshot(null, null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] buffer = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), buffer, 0, width);
        return buffer;
    }

    private static DesktopState onlineWithConversation() {
        DesktopState state = new DesktopState();
        state.online("0.1.0");
        state.diagnostics(Map.of(
                "core", Map.of("version", "0.1.0", "uptimeSeconds", 180),
                "providers", Map.of("local", "configurado (local)"),
                "roles", Map.of("conversation", Map.of("provider", "local", "model", "gpt-local", "ready", true))));
        return state;
    }
}
