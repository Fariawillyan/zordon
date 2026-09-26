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
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.VoicePresentation;
import zordon.desktop.shell.VoiceStatus;
import zordon.zwp.CoreConnection;

/**
 * A tela de Voz da SPEC-010: o console em tela cheia. Em repouso ela é igual à
 * imagem de referência; fora dele, uma pílula no alto diz o estado real da voz
 * ou do núcleo e, com o microfone ligado, oferece "Desligar" (SPEC-006).
 */
@Spec("SPEC-010")
final class VoiceView extends StackPane {

    private final VoiceEffectsPane effects = new VoiceEffectsPane();
    private final DesktopState state;
    /** Só ícone: o rótulo da SPEC-006 vai para o leitor de tela e a dica (SPEC-012 CA-7). */
    private final Label pillIcon = new Label();
    private final javafx.scene.control.Tooltip pillHint = new javafx.scene.control.Tooltip();
    private final Button turnOff = new Button();
    private final HBox pill;
    private final VoiceStatusStrip strip;

    VoiceView(DesktopState state, ShellActions actions) {
        this.state = state;
        getStyleClass().add("voice-page");
        setMinSize(0, 0);

        pillIcon.getStyleClass().add("pill-icon");
        turnOff.getStyleClass().add("pill-action");
        turnOff.setId("voice-turn-off");
        turnOff.setGraphic(Icons.of("micoff", 16, javafx.scene.paint.Color.web("#04141B")));
        turnOff.setAccessibleText("Desligar o microfone");
        turnOff.setTooltip(new javafx.scene.control.Tooltip("Desligar o microfone"));
        turnOff.setOnAction(event -> actions.setVoiceMode("off"));
        pill = new HBox(8, pillIcon, turnOff);
        pill.setId("voice-pill");
        pill.getStyleClass().add("voice-pill");
        pill.setAlignment(Pos.CENTER);
        pill.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        effects.topCenter(pill);
        effects.onTalk(() -> toggleListening(state, actions));
        effects.modeControl(new VoiceModePicker(state, actions, true));
        // O console manda na tela e a faixa de estado fecha embaixo (SPEC-032).
        this.strip = new VoiceStatusStrip(state);
        javafx.scene.layout.VBox column = new javafx.scene.layout.VBox(effects, strip);
        column.setMinSize(0, 0);
        javafx.scene.layout.VBox.setVgrow(effects, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(column);

        visibleProperty().addListener((observable, before, now) -> effects.active(now));
        effects.active(isVisible());
        state.voiceProperty().addListener((observable, before, now) -> {
            effects.feedback(before, now);
            showPill();
        });
        state.connectionProperty().addListener((observable, before, now) -> showPill());
        state.lockdownProperty().addListener((observable, before, now) -> showPill());
        javafx.scene.control.Tooltip.install(pill, pillHint);
        // O estado visual do núcleo e o nível do microfone chegam do núcleo (SPEC-012).
        state.activityProperty().addListener((observable, before, now) -> effects.activity(now));
        effects.activity(state.activityProperty().get());
        state.oppressorProperty().addListener((observable, before, now) -> effects.oppressor(now));
        effects.oppressor(state.oppressorProperty().get());
        state.voiceLevelProperty().addListener((observable, before, now) -> {
            if (now != null) {
                effects.micLevel(now);
            }
        });
        showPill();
    }

    VoiceStatusStrip statusStrip() {
        return strip;
    }

    /** Clicar na esfera começa a escuta; clicar de novo, durante ela, encerra (SPEC-011 CA-7). */
    static void toggleListening(DesktopState state, ShellActions actions) {
        VoiceStatus voice = state.voiceProperty().get();
        if (voice != null && "listening".equals(voice.activity())) {
            actions.stopListening();
        } else {
            actions.startListening();
        }
    }

    private void showPill() {
        CoreConnection.State connection = state.connectionProperty().get();
        VoiceStatus voice = state.voiceProperty().get();
        ZoneId zone = ZoneId.systemDefault();
        boolean paused = connection == CoreConnection.State.ONLINE && state.lockdownProperty().get();
        boolean rest = !paused && VoicePresentation.atRest(connection, voice, zone);
        pill.setVisible(!rest);
        // Lockdown vem antes da voz: é o estado que o usuário precisa ver primeiro (SPEC-015 CA-6).
        String label = paused ? zordon.desktop.shell.SecurityPresentation.PAUSED
                : VoicePresentation.pillText(connection, voice, zone);
        pill.setAccessibleText(label);
        pillIcon.setAccessibleText(label);
        pillHint.setText(label);
        boolean offer = connection == CoreConnection.State.ONLINE && VoicePresentation.offersTurnOff(voice);
        boolean live = offer || voice != null && voice.testing();
        pillIcon.setGraphic(Icons.of(live ? "mic" : "micoff", 16,
                javafx.scene.paint.Color.web(live ? "#12E3F7" : "#F0B341")));
        turnOff.setVisible(offer);
        turnOff.setManaged(offer);
        pill.getStyleClass().removeAll("pill-live", "pill-warning");
        pill.getStyleClass().add(offer || voice != null && voice.testing() ? "pill-live" : "pill-warning");
    }

    boolean pillVisible() {
        return pill.isVisible();
    }

    /** O rótulo da pílula, como o leitor de tela o anuncia. */
    String pillText() {
        return pill.getAccessibleText();
    }

    /** Textos visíveis da pílula; voice-first, precisa ser vazio (SPEC-012 CA-7). */
    java.util.List<String> pillVisibleTexts() {
        return pill.lookupAll(".label").stream()
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    String activity() {
        return effects.activity();
    }

    void close() {
        effects.close();
    }
}
