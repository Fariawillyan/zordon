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

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.VoiceDevice;
import zordon.desktop.shell.VoicePresentation;
import zordon.desktop.shell.VoiceStatus;

/** Os cartões do microfone nos Ajustes da voz: o teste com o medidor e a escolha do dispositivo. */
final class MicrophoneCards {

    private final DesktopState state;
    private final ShellActions actions;
    private ProgressBar liveMeter;
    private Label liveLevel;

    MicrophoneCards(DesktopState state, ShellActions actions) {
        this.state = state;
        this.actions = actions;
    }

    /**
     * Teste do microfone (SPEC-009): o medidor mostra o nível que o núcleo recebe,
     * e não um sinal local; é a prova de que o caminho host → núcleo funciona.
     */
    Node test(VoiceStatus voice) {
        VoicePresentation.TestCard card = VoicePresentation.testCard(voice);
        Button start = new Button(VoicePresentation.TEST_BUTTON);
        start.setId("microphone-test");
        start.getStyleClass().add("button-secondary");
        start.setDisable(!card.enabled());
        start.setOnAction(event -> actions.voice().testMicrophone());

        ProgressBar meter = new ProgressBar(0);
        meter.setId("microphone-meter");
        meter.getStyleClass().add("microphone-meter");
        meter.setMaxWidth(Double.MAX_VALUE);
        meter.setAccessibleText("Nível do microfone");
        Label level = new Label("—");
        level.getStyleClass().add("row-value");
        boolean testing = voice != null && voice.testing();
        meter.setVisible(testing);
        meter.setManaged(testing);
        level.setVisible(testing);
        level.setManaged(testing);
        // O listener único do construtor atualiza estes dois enquanto forem os atuais.
        liveMeter = testing ? meter : null;
        liveLevel = testing ? level : null;

        Label hint = SettingsRows.muted(card.hint());
        VBox body = new VBox(8, SettingsRows.row(hint, start), meter, level);
        if (!card.result().isEmpty()) {
            Label result = new Label(card.result());
            result.setId("microphone-result");
            result.setWrapText(true);
            result.getStyleClass().add(card.resultOk() ? "body-text" : "warning-text");
            body.getChildren().add(result);
        }
        return Cards.section("Teste do microfone", body);
    }

    /** O microfone: a captura confirmada, o host do Windows e a escolha do dispositivo. */
    Node devices(VoiceStatus voice, VoicePresentation.Screen screen) {
        Label capture = new Label(screen.capture());
        capture.setWrapText(true);
        boolean unconfirmed = voice.hostConnected() && !"on".equals(voice.capture()) && !"off".equals(voice.capture());
        capture.getStyleClass().add(unconfirmed ? "warning-text" : "body-text");

        ComboBox<VoiceDevice> devices = new ComboBox<>();
        devices.getItems().setAll(state.voice().devices());
        devices.setPromptText(voice.hostConnected() ? "Atualize para listar" : "Sem host do Windows");
        state.voice().devices().stream().filter(VoiceDevice::selected).findFirst().ifPresent(devices::setValue);
        devices.setDisable(!voice.hostConnected() || state.voice().devices().isEmpty());
        devices.setOnAction(event -> {
            VoiceDevice chosen = devices.getValue();
            if (chosen != null && !chosen.selected()) {
                actions.voice().selectVoiceDevice(chosen.id());
            }
        });
        Button refresh = new Button("Atualizar lista");
        refresh.getStyleClass().add("button-secondary");
        refresh.setDisable(!voice.hostConnected());
        refresh.setOnAction(event -> actions.voice().loadVoiceDevices());
        HBox picker = new HBox(8, devices, refresh);

        return Cards.section("Microfone", new VBox(12,
                capture,
                Inspector.rows("Host do Windows", screen.host(), "Dispositivo", screen.device()),
                picker));
    }

    void showLevel(double[] level) {
        if (level == null || liveMeter == null) {
            return;
        }
        liveMeter.setProgress(VoicePresentation.meter(level[0]));
        liveLevel.setText(VoicePresentation.dbfs(level[0]) + " · pico " + VoicePresentation.dbfs(level[1]));
    }
}
