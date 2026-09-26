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

import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import zordon.desktop.audio.SoundSynthesizer;
import zordon.desktop.audio.SoundSynthesizer.Cue;

/**
 * O cartão de Ajustes do console de voz: o modo de voz, o sinal de teste, os
 * efeitos, o volume e a linha que diz onde o som toca.
 */
final class VoiceEffectsSettings extends VBox {

    private final Button stop = new Button("■  Parar");
    private final Label status = new Label("●  PRONTO PARA TESTAR");
    /** Onde o seletor de modo de voz entra, quando a tela o fornece (SPEC-033). */
    private final VBox modeSlot = new VBox();
    private final ComboBox<Cue> cue = new ComboBox<>();
    private final Slider volume = new Slider(0, 100, 35);
    private final ToggleButton mute = new ToggleButton("Silenciar");
    private final CheckBox echo = option("Eco", true);
    private final CheckBox reverb = option("Reverberação", true);
    private final CheckBox soft = option("Filtro suave", false);
    private final CheckBox stereo = option("Abertura estéreo", true);
    private final CheckBox automatic = option("Feedback automático da voz", false);
    private final CheckBox reduced = option("Reduzir movimento", false);

    /**
     * @param pause para o som: o botão "Parar", silenciar e desligar o feedback automático
     * @param volumeChanged o volume ou o mudo mudou
     * @param reducedMotion "Reduzir movimento" mudou
     */
    VoiceEffectsSettings(Runnable pause, Runnable volumeChanged, Consumer<Boolean> reducedMotion) {
        super(14);
        stop.getStyleClass().add("button-secondary");
        stop.setId("voice-stop");
        stop.setDisable(true);
        stop.setOnAction(event -> pause.run());

        cue.getItems().setAll(Cue.values());
        cue.setValue(Cue.ACTIVATE);
        cue.setAccessibleText("Sinal sonoro para testar");
        cue.setId("voice-cue");
        FlowPane effects = new FlowPane(16, 12, echo, reverb, soft, stereo);
        FlowPane preferences = new FlowPane(16, 12, automatic, reduced);
        Label help = new Label("Efeitos aplicados ao próximo sinal. O volume também ajusta o som atual."
                + "\nÁudio local de feedback; não altera nem testa o microfone. Ajustes válidos nesta sessão.");
        help.getStyleClass().add("voice-caption");
        help.setWrapText(true);
        // O modo de voz entra no topo: é o ajuste que o usuário vem procurar aqui.
        VBox settingsBody = new VBox(14, modeSlot,
                new FlowPane(12, 8, new Label("Sinal"), cue, stop), effects, preferences, help);
        settingsBody.getStyleClass().add("voice-settings");
        settingsBody.setId("voice-settings");
        reduced.selectedProperty().addListener((obs, old, value) -> reducedMotion.accept(value));
        automatic.selectedProperty().addListener((obs, old, value) -> { if (!value) pause.run(); });

        volume.setId("voice-volume");
        volume.setAccessibleText("Volume dos efeitos sonoros");
        volume.setPrefWidth(125);
        volume.setMaxWidth(125);
        volume.setBlockIncrement(5);
        Label percentage = new Label("35%");
        percentage.setMinWidth(36);
        percentage.getStyleClass().add("voice-caption");
        volume.valueProperty().addListener((obs, old, value) -> {
            percentage.setText(Math.round(value.doubleValue()) + "%");
            volumeChanged.run();
        });
        mute.getStyleClass().add("mode-option");
        mute.setId("voice-mute");
        mute.selectedProperty().addListener((obs, old, value) -> {
            mute.setText(value ? "Ativar som" : "Silenciar");
            if (value) pause.run();
            volumeChanged.run();
        });
        HBox volumeBox = new HBox(10, new Label("Volume"), volume, percentage, mute);
        volumeBox.setAlignment(Pos.CENTER_LEFT);
        volumeBox.getStyleClass().add("voice-volume-box");
        status.getStyleClass().add("voice-output-status");
        status.setWrapText(true);
        status.setMaxWidth(330);
        status.setId("voice-output-status");
        getStyleClass().add("console-card");
        getChildren().addAll(settingsBody, volumeBox, status);
    }

    /** Põe o seletor de modo no alto do cartão. */
    void modeControl(Node control) {
        modeSlot.getChildren().setAll(control);
    }

    Cue cue() {
        return cue.getValue();
    }

    SoundSynthesizer.Settings effects() {
        return new SoundSynthesizer.Settings(
                echo.isSelected(), reverb.isSelected(), soft.isSelected(), stereo.isSelected());
    }

    boolean automaticFeedback() {
        return automatic.isSelected();
    }

    /** Mudo, ou volume no zero. */
    boolean silenced() {
        return mute.isSelected() || volume.getValue() == 0;
    }

    /** O volume de 0 a 1, já com o mudo aplicado. */
    double level() {
        return mute.isSelected() ? 0 : volume.getValue() / 100;
    }

    /** A linha de status; "Parar" só habilita com som tocando. */
    void show(String text, boolean playing) {
        status.setText(text);
        stop.setDisable(!playing);
    }

    private static CheckBox option(String text, boolean selected) {
        CheckBox check = new CheckBox(text);
        check.setSelected(selected);
        return check;
    }
}
