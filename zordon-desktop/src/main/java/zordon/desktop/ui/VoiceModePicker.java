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

import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.VoicePresentation;
import zordon.desktop.shell.VoiceStatus;

/**
 * Escolher quando o Zordon escuta (SPEC-033): desligada, aguardando a palavra,
 * por atalho, ou conversa aberta por 5 minutos.
 *
 * <p>Vive em dois lugares porque a decisão é a mesma: nos Ajustes, e no painel de
 * Ajustes do próprio console — onde o usuário está quando percebe que quer mudar.
 */
@Spec("SPEC-033")
final class VoiceModePicker extends VBox {

    static final List<String> MODES = List.of("off", "wake", "push", "open");

    private final DesktopState state;
    private final ShellActions actions;
    private final FlowPane options = new FlowPane(6, 8);
    private final Label note = new Label();

    /**
     * @param compact no painel do console, onde o espaço é menor
     *
     * <p>Cada instância tem id próprio: duas com o mesmo id deixariam
     * {@code lookup} ambíguo, e um teste passaria achando a errada. A classe de
     * estilo {@code voice-mode-picker} é o que as une.
     */
    VoiceModePicker(DesktopState state, ShellActions actions, boolean compact) {
        super(6);
        this.state = state;
        this.actions = actions;
        setId(compact ? "voice-mode-picker-console" : "voice-mode-picker-settings");
        getStyleClass().add("voice-mode-picker");
        options.setPrefWrapLength(compact ? 300 : 450);
        note.getStyleClass().add("voice-caption");
        note.setWrapText(true);
        Label title = new Label("Modo");
        if (compact) {
            title.getStyleClass().add("status-card-title");
        }
        getChildren().addAll(title, options, note);
        state.voice().statusProperty().addListener((observable, before, now) -> render());
        render();
    }

    private void render() {
        VoiceStatus voice = state.voice().statusProperty().get();
        ToggleGroup group = new ToggleGroup();
        options.getChildren().clear();
        for (String mode : MODES) {
            ToggleButton option = new ToggleButton(VoicePresentation.modeLabel(mode));
            option.setId("mode-" + mode);
            option.getStyleClass().add("mode-option");
            option.setToggleGroup(group);
            option.setSelected(voice != null && mode.equals(voice.mode()));
            option.setDisable(voice == null);
            // A seleção mostra o que o núcleo confirmou, não o que se acabou de
            // pedir: o botão volta para o modo em vigor e só muda quando o
            // VOICE_STATE chegar. Marcar na hora mentiria se o pedido falhasse.
            option.setOnAction(event -> {
                VoiceStatus atual = state.voice().statusProperty().get();
                group.selectToggle(atual == null ? null : group.getToggles().stream()
                        .filter(toggle -> atual.mode().equals(((ToggleButton) toggle).getId()
                                .substring("mode-".length())))
                        .findFirst().orElse(null));
                actions.voice().setVoiceMode(mode);
            });
            options.getChildren().add(option);
        }
        options.setAlignment(Pos.CENTER_LEFT);
        note.setText(voice == null ? "Esperando o núcleo responder sobre a voz." : explain(voice.mode()));
    }

    /** O que cada modo faz, em uma linha — o nome do botão não basta. */
    static String explain(String mode) {
        return switch (mode) {
            case "off" -> "Microfone desligado no Windows. Nada é capturado.";
            case "wake" -> "Sempre atento: diga \"Zordon\" e ele atende.";
            case "push" -> "Grava só enquanto o atalho global estiver pressionado.";
            case "open" -> "Conversa contínua por 5 minutos, sem precisar repetir \"Zordon\".";
            default -> "";
        };
    }
}
