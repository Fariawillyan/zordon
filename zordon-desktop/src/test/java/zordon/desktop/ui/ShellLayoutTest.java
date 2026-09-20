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

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    @AcceptanceCriteria("SPEC-010/CA-1")
    @Test
    void aJanelaTemSoOTrilhoEATelaEAbreNaVoz() throws Exception {
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
            assertThat(shell.rail().lookupAll(".rail-item")).hasSize(4);
            shell.close();
            stage.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-010/CA-8")
    @ParameterizedTest(name = "{0}×{1}")
    @CsvSource({"960, 720", "720, 560"})
    void oConsoleCabeSemRolagemEOTrilhoMantem56(int width, int height) throws Exception {
        onFx(() -> {
            ZordonShell shell = new ZordonShell(onlineWithConversation(), NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(shell, width, height));
            stage.show();
            shell.applyCss();
            shell.layout();
            assertThat(shell.rail().getWidth()).isEqualTo(56);
            assertThat(shell.voice().getWidth()).isLessThanOrEqualTo(width - 56.0 + 0.5);
            assertThat(shell.voice().getBoundsInParent().getMaxX()).isLessThanOrEqualTo(width - 56.0 + 0.5);
            shell.close();
            stage.close();
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
            state.voice(Map.of(
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
            state.select(zordon.desktop.shell.Destination.SETTINGS);
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
            state.voice(Map.of(
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
            state.select(zordon.desktop.shell.Destination.SETTINGS);
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
            state.voice(Map.of(
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
            assertThat(shell.rail().getWidth()).isEqualTo(56);
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
            javafx.scene.Node core = shell.rail().lookup(".rail-core");

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

    @AcceptanceCriteria("SPEC-010/CA-5")
    @Test
    void oPainelLevaALogsEDiagnosticoEMostraOsFuturosComOMarco() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            // O Painel é do modo técnico (SPEC-012 CA-8).
            state.technicalModeProperty().set(true);
            state.select(zordon.desktop.shell.Destination.HOME);
            javafx.scene.Parent content = (javafx.scene.Parent)
                    ((javafx.scene.control.ScrollPane) shell.lookup(".page")).getContent();

            javafx.scene.control.Button logs = (javafx.scene.control.Button) content.lookup("#tile-logs");
            javafx.scene.control.Button mcp = (javafx.scene.control.Button) content.lookup("#tile-mcp");
            assertThat(mcp.getText()).contains("M4");
            assertThat(mcp.getStyleClass()).contains("tile-unavailable");

            logs.fire();
            assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.LOGS);
            assertThat(state.destinationProperty().get().railOwner()).isEqualTo(zordon.desktop.shell.Destination.HOME);
            mcp.fire();
            assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.LOGS);
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
            state.voice(Map.of(
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
            state.voice(Map.of("mode", "wake", "effective", "wake", "activity", "idle",
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

    @AcceptanceCriteria("SPEC-012/CA-8")
    @Test
    void oModoTecnicoComecaDesligadoEEscondeOPainelEOsMetadados() throws Exception {
        onFx(() -> {
            DesktopState state = onlineWithConversation();
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            shell.chatAssistant("São 15h40.", "local · 12 ms · 0 tokens");
            javafx.scene.Node panel = shell.rail().lookupAll(".rail-item").stream()
                    .filter(node -> "Painel".equals(node.getAccessibleText())).findFirst().orElseThrow();

            assertThat(state.technicalModeProperty().get()).isFalse();
            assertThat(panel.isVisible()).isFalse();
            assertThat(state.select(zordon.desktop.shell.Destination.LOGS)).isFalse();

            state.technicalModeProperty().set(true);
            assertThat(panel.isVisible()).isTrue();
            assertThat(state.select(zordon.desktop.shell.Destination.DIAGNOSTICS)).isTrue();

            state.technicalModeProperty().set(false);
            assertThat(state.destinationProperty().get()).isEqualTo(zordon.desktop.shell.Destination.VOICE);
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-012/CA-8")
    @Test
    void osMetadadosDaRespostaSoAparecemNoModoTecnico() throws Exception {
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

            assertThat(visibleMeta(shell)).isEmpty();
            state.technicalModeProperty().set(true);
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
