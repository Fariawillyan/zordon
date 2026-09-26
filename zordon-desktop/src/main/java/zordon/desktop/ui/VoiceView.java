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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.VoiceStatus;

/**
 * A tela de Voz da SPEC-010: o console em tela cheia. Em repouso ela é igual à
 * imagem de referência; fora dele, uma pílula no alto diz o estado real da voz
 * ou do núcleo e, com o microfone ligado, oferece "Desligar" (SPEC-006).
 */
@Spec("SPEC-010")
final class VoiceView extends StackPane {

    private final VoiceEffectsPane effects = new VoiceEffectsPane();
    private final VoicePill pill;
    private final VoiceStatusStrip strip;

    VoiceView(DesktopState state, ShellActions actions) {
        getStyleClass().add("voice-page");
        setMinSize(0, 0);

        pill = new VoicePill(state, actions);
        effects.topCenter(pill);
        effects.onTalk(() -> toggleListening(state, actions));
        effects.modeControl(new VoiceModePicker(state, actions, true));
        // O console manda na tela e a faixa de estado fecha embaixo (SPEC-032).
        this.strip = new VoiceStatusStrip(state);
        VBox column = new VBox(effects, strip);
        column.setMinSize(0, 0);
        VBox.setVgrow(effects, Priority.ALWAYS);
        getChildren().add(column);

        visibleProperty().addListener((observable, before, now) -> effects.active(now));
        effects.active(isVisible());
        state.voice().statusProperty().addListener((observable, before, now) -> effects.feedback(before, now));
        // O estado visual do núcleo e o nível do microfone chegam do núcleo (SPEC-012).
        state.voice().activityProperty().addListener((observable, before, now) -> effects.activity(now));
        effects.activity(state.voice().activityProperty().get());
        state.security().oppressorProperty().addListener((observable, before, now) -> effects.oppressor(now));
        effects.oppressor(state.security().oppressorProperty().get());
        state.voice().levelProperty().addListener((observable, before, now) -> {
            if (now != null) {
                effects.micLevel(now);
            }
        });
        // Ctrl+Espaço fala com o Zordon de qualquer tela (SPEC-011 CA-7): a tela
        // de Voz está sempre na cena, mesmo escondida, e registra o atalho nela.
        sceneProperty().addListener((observable, before, scene) -> {
            if (scene != null) {
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN),
                        () -> toggleListening(state, actions));
            }
        });
    }

    VoiceStatusStrip statusStrip() {
        return strip;
    }

    /** Clicar na esfera começa a escuta; clicar de novo, durante ela, encerra (SPEC-011 CA-7). */
    static void toggleListening(DesktopState state, ShellActions actions) {
        VoiceStatus voice = state.voice().statusProperty().get();
        if (voice != null && "listening".equals(voice.activity())) {
            actions.voice().stopListening();
        } else {
            actions.voice().startListening();
        }
    }

    boolean pillVisible() {
        return pill.isVisible();
    }

    /** O rótulo da pílula, como o leitor de tela o anuncia. */
    String pillText() {
        return pill.getAccessibleText();
    }

    /** Textos visíveis da pílula; voice-first, precisa ser vazio (SPEC-012 CA-7). */
    List<String> pillVisibleTexts() {
        return pill.visibleTexts();
    }

    String activity() {
        return effects.activity();
    }

    void close() {
        effects.close();
    }
}
