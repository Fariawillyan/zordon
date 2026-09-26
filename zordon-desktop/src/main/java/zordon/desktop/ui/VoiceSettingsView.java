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

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.VoiceDevice;
import zordon.desktop.shell.VoicePresentation;
import zordon.desktop.shell.VoiceStatus;

/**
 * Ajustes da voz (SPEC-010 §3): modo, microfone, dispositivo, teste do
 * microfone, motor e transcrições — o que saiu da tela de Voz, que agora é só o
 * console.
 *
 * <p>Nada aqui muda de estado sozinho. Escolher um modo pede ao núcleo e a tela
 * redesenha com o que ele responder — é assim que "desligado" só aparece
 * confirmado.
 */
@Spec("SPEC-010")
final class VoiceSettingsView extends ScrollPane {

    private final VBox content = new VBox(12);
    private final Map<String, VBox> sections = new LinkedHashMap<>();
    private final Map<String, ToggleButton> sectionButtons = new LinkedHashMap<>();
    private final VBox modeArea = new VBox(8);
    private final VBox details = new VBox(12);
    private final DesktopState state;
    private final ShellActions actions;
    private final MicrophoneCards microphone;

    VoiceSettingsView(DesktopState state, ShellActions actions) {
        this.state = state;
        this.actions = actions;
        this.microphone = new MicrophoneCards(state, actions);
        getStyleClass().addAll("page", "settings-page");
        setFitToWidth(true);
        content.setPadding(new Insets(16, 16, 24, 16));
        content.setFillWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        content.getChildren().addAll(modeArea, details);
        // Sem isto, o conteúdo dita a largura mínima e o console transborda na
        // janela de 720 (SPEC-010 CA-2). O laço das abas fazia isso por seção.
        content.setMinWidth(0);
        modeArea.setMinWidth(0);
        details.setMinWidth(0);
        setMinWidth(0);
        setContent(content);
        state.voice().statusProperty().addListener((observable, before, now) -> {
            render();
        });
        state.voice().errorProperty().addListener((observable, before, now) -> render());
        state.voice().levelProperty().addListener((observable, before, now) -> microphone.showLevel(now));
        state.voice().devices().addListener((ListChangeListener<VoiceDevice>) change -> render());
        state.voice().transcriptions().addListener((ListChangeListener<String>) change -> render());
        render();
    }

    private void render() {
        modeArea.getChildren().clear();
        details.getChildren().clear();
        if (!state.voice().errorProperty().get().isEmpty()) {
            modeArea.getChildren().add(SettingsRows.warning("Falha ao falar com o núcleo sobre a voz: "
                    + state.voice().errorProperty().get()));
        }
        VoiceStatus voice = state.voice().statusProperty().get();
        VoicePresentation.Screen screen = voice == null ? null
                : VoicePresentation.screen(voice, ZoneId.systemDefault());
        modeArea.getChildren().addAll(mode(voice, screen), microphone.test(voice));
        if (voice == null) {
            modeArea.getChildren().add(SettingsRows.muted("Estado da voz desconhecido enquanto o núcleo não responde."));
            return;
        }
        if (!screen.reason().isEmpty()) modeArea.getChildren().add(SettingsRows.warning(screen.reason()));
        details.getChildren().addAll(
                Inspector.rows("Pedido", screen.desired(), "Em vigor", screen.effective()),
                microphone.devices(voice, screen),
                Cards.section("Motor de voz", new VBox(8,
                        Inspector.rows("Estado", screen.engine()),
                        SettingsRows.muted("VAD, palavra de ativação, transcrição e fala. Chega com o sidecar zordon-voice."))),
                transcriptions());
    }

    private Node mode(VoiceStatus voice, VoicePresentation.Screen screen) {
        // Um seletor só, usado aqui e no painel do console (SPEC-033).
        Node choice = new VoiceModePicker(state, actions, false);
        String microphone = voice == null ? "Estado desconhecido"
                : !voice.hostConnected() ? "Não conectado"
                : "on".equals(voice.capture()) ? "Ligado · confirmado"
                : "off".equals(voice.capture()) ? "Desligado · confirmado" : screen.capture();
        String engine = screen == null ? "Estado desconhecido" : screen.engine();
        FlowPane strip = new FlowPane(12, 12, choice,
                summary("Microfone", microphone), summary("Motor de voz", engine));
        strip.getStyleClass().add("voice-mode-strip");
        return strip;
    }

    private static Node summary(String title, String value) {
        Label label = new Label(title);
        label.getStyleClass().add("voice-caption");
        Label status = new Label("●  " + value);
        status.setWrapText(true);
        status.getStyleClass().add("voice-caption");
        VBox box = new VBox(7, label, status);
        box.setPrefWidth(122);
        box.setMaxWidth(160);
        box.getStyleClass().add("voice-status-card");
        return box;
    }

    private Node transcriptions() {
        VBox list = new VBox(6);
        if (state.voice().transcriptions().isEmpty()) {
            list.getChildren().add(SettingsRows.muted("Nenhuma transcrição nesta sessão."));
        } else {
            state.voice().transcriptions().forEach(line -> {
                Label entry = new Label(line);
                entry.setWrapText(true);
                entry.getStyleClass().add("row-value");
                list.getChildren().add(entry);
            });
        }
        return Cards.section("Transcrições desta sessão", list);
    }

}
