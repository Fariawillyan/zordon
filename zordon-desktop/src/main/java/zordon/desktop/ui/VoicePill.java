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
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.SecurityPresentation;
import zordon.desktop.shell.VoicePresentation;
import zordon.desktop.shell.VoiceStatus;
import zordon.zwp.CoreConnection;

/**
 * A pílula de estado no alto do console (SPEC-006, SPEC-015): some em repouso,
 * avisa o lockdown antes da voz e oferece desligar o microfone quando ele está
 * aberto. Só ícone: o rótulo vai para o leitor de tela e a dica (SPEC-012 CA-7).
 */
final class VoicePill extends HBox {

    private final DesktopState state;
    private final Label pillIcon = new Label();
    private final Tooltip pillHint = new Tooltip();
    private final Button turnOff = new Button();

    VoicePill(DesktopState state, ShellActions actions) {
        super(8);
        this.state = state;
        pillIcon.getStyleClass().add("pill-icon");
        turnOff.getStyleClass().add("pill-action");
        turnOff.setId("voice-turn-off");
        turnOff.setGraphic(Icons.of("micoff", 16, Color.web("#04141B")));
        turnOff.setAccessibleText("Desligar o microfone");
        turnOff.setTooltip(new Tooltip("Desligar o microfone"));
        turnOff.setOnAction(event -> actions.voice().setVoiceMode("off"));
        getChildren().addAll(pillIcon, turnOff);
        setId("voice-pill");
        getStyleClass().add("voice-pill");
        setAlignment(Pos.CENTER);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        Tooltip.install(this, pillHint);
        state.voice().statusProperty().addListener((observable, before, now) -> refresh());
        state.connection().stateProperty().addListener((observable, before, now) -> refresh());
        state.security().lockdownProperty().addListener((observable, before, now) -> refresh());
        refresh();
    }

    void refresh() {
        CoreConnection.State connection = state.connection().stateProperty().get();
        VoiceStatus voice = state.voice().statusProperty().get();
        ZoneId zone = ZoneId.systemDefault();
        boolean paused = connection == CoreConnection.State.ONLINE && state.security().lockdownProperty().get();
        boolean rest = !paused && VoicePresentation.atRest(connection, voice, zone);
        setVisible(!rest);
        // Lockdown vem antes da voz: é o estado que o usuário precisa ver primeiro (SPEC-015 CA-6).
        String label = paused ? SecurityPresentation.PAUSED : VoicePresentation.pillText(connection, voice, zone);
        setAccessibleText(label);
        pillIcon.setAccessibleText(label);
        pillHint.setText(label);
        boolean offer = connection == CoreConnection.State.ONLINE && VoicePresentation.offersTurnOff(voice);
        boolean live = offer || voice != null && voice.testing();
        pillIcon.setGraphic(Icons.of(live ? "mic" : "micoff", 16, Color.web(live ? "#12E3F7" : "#F0B341")));
        turnOff.setVisible(offer);
        turnOff.setManaged(offer);
        getStyleClass().removeAll("pill-live", "pill-warning");
        getStyleClass().add(offer || voice != null && voice.testing() ? "pill-live" : "pill-warning");
    }

    /** Textos visíveis da pílula; voice-first, precisa ser vazio (SPEC-012 CA-7). */
    List<String> visibleTexts() {
        return lookupAll(".label").stream()
                .map(node -> ((Labeled) node).getText())
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }
}
