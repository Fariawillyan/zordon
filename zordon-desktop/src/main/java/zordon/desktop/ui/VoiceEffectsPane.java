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

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import zordon.api.trace.Spec;
import zordon.desktop.audio.SoundPlayer;
import zordon.desktop.audio.SoundSynthesizer;
import zordon.desktop.audio.SoundSynthesizer.Cue;
import zordon.desktop.shell.VoiceStatus;

/** Local feedback controls, deliberately independent of microphone availability. */
@Spec("SPEC-008")
final class VoiceEffectsPane extends Pane {
    private final SoundPlayer player;
    /** A saída padrão, lida uma vez: consultar o sistema a 30 fps custaria caro. */
    private final String outputName;
    private final VoiceVisualizer visualizer;
    private final Button test = new Button("▶  Testar som");
    private final Button stop = new Button("■  Parar");
    private final Label status = new Label("●  PRONTO PARA TESTAR");
    /** Resultado do último som, sob o medidor; some em repouso, como na referência. */
    private final Label caption = new Label();
    private final HBox buttons = new HBox(14);
    private final VBox card = new VBox(14);
    private Node topCenter;
    /** A esfera é o botão de falar (SPEC-011 CA-7): invisível, circular, acessível. */
    private final Button talk = new Button();
    private final ComboBox<Cue> cue = new ComboBox<>();
    private final Slider volume = new Slider(0, 100, 35);
    private final ToggleButton mute = new ToggleButton("Silenciar");
    private final CheckBox echo = option("Eco", true);
    private final CheckBox reverb = option("Reverberação", true);
    private final CheckBox soft = option("Filtro suave", false);
    private final CheckBox stereo = option("Abertura estéreo", true);
    private final CheckBox automatic = option("Feedback automático da voz", false);
    private final CheckBox reduced = option("Reduzir movimento", false);
    private boolean active;
    private boolean timerRunning;
    private long lastFrame;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (now - lastFrame < 33_333_333) return;
            lastFrame = now;
            visualizer.tick();
            refreshStatus();
            if (!player.playing()) stopTimer();
        }
    };
    private final ChangeListener<Boolean> showingListener = (obs, before, now) -> {
        if (!now) pause();
    };
    private final ChangeListener<Window> windowListener = (obs, before, now) -> observeWindow(before, now);

    VoiceEffectsPane() { this(new SoundPlayer()); }

    VoiceEffectsPane(SoundPlayer player) {
        this(player, SoundPlayer.outputName());
    }

    VoiceEffectsPane(SoundPlayer player, String outputName) {
        this.player = player;
        this.outputName = outputName;
        visualizer = new VoiceVisualizer(player);
        getStyleClass().add("voice-console");
        setId("voice-effects");
        test.getStyleClass().addAll("console-button", "console-primary");
        test.setId("voice-test");
        test.setOnAction(event -> { if (player.playing()) pause(); else play(cue.getValue()); });
        stop.getStyleClass().add("button-secondary");
        stop.setId("voice-stop");
        stop.setDisable(true);
        stop.setOnAction(event -> pause());
        ToggleButton settings = new ToggleButton("Ajustes",
                Icons.of("settings", 14, javafx.scene.paint.Color.web("#C7D6E2")));
        settings.getStyleClass().add("console-button");
        settings.setId("voice-settings-toggle");
        buttons.getChildren().addAll(test, settings);

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
        VBox settingsBody = new VBox(14, new FlowPane(12, 8, new Label("Sinal"), cue, stop), effects, preferences, help);
        settingsBody.getStyleClass().add("voice-settings");
        settingsBody.setId("voice-settings");
        card.visibleProperty().bind(settings.selectedProperty());
        reduced.selectedProperty().addListener((obs, old, value) -> visualizer.reducedMotion(value));
        automatic.selectedProperty().addListener((obs, old, value) -> { if (!value) pause(); });

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
            updateVolume();
        });
        mute.getStyleClass().add("mode-option");
        mute.setId("voice-mute");
        mute.selectedProperty().addListener((obs, old, value) -> {
            mute.setText(value ? "Ativar som" : "Silenciar");
            if (value) pause();
            updateVolume();
        });
        HBox volumeBox = new HBox(10, new Label("Volume"), volume, percentage, mute);
        volumeBox.setAlignment(Pos.CENTER_LEFT);
        volumeBox.getStyleClass().add("voice-volume-box");
        status.getStyleClass().add("voice-output-status");
        status.setWrapText(true);
        status.setMaxWidth(330);
        status.setId("voice-output-status");
        caption.getStyleClass().add("console-caption");
        caption.setId("voice-output-caption");
        caption.setWrapText(true);
        card.getStyleClass().add("console-card");
        card.getChildren().addAll(settingsBody, volumeBox, status);
        talk.setId("voice-talk");
        talk.getStyleClass().add("orb-button");
        talk.setAccessibleText("Falar com o Zordon");
        talk.setTooltip(new javafx.scene.control.Tooltip("Falar com o Zordon (Ctrl+Espaço)"));
        getChildren().addAll(visualizer, talk, caption, buttons, card);
        sceneProperty().addListener((obs, before, now) -> observeScene(before, now));
    }

    private void observeScene(Scene before, Scene now) {
        if (before != null) {
            before.windowProperty().removeListener(windowListener);
            observeWindow(before.getWindow(), null);
        }
        if (now != null) {
            now.windowProperty().addListener(windowListener);
            observeWindow(null, now.getWindow());
        } else pause();
    }

    private void observeWindow(Window before, Window now) {
        if (before != null) before.showingProperty().removeListener(showingListener);
        if (now != null) now.showingProperty().addListener(showingListener);
    }

    void active(boolean value) { active = value; if (!value) pause(); }

    /** O que fazer quando a esfera é acionada. */
    void onTalk(Runnable action) {
        talk.setOnAction(event -> action.run());
    }

    /** Estado visual do núcleo (SPEC-012): o visualizador anima, sem texto. */
    void activity(String state) {
        visualizer.activity(state);
    }

    void micLevel(double dbfs) {
        visualizer.micLevel(dbfs);
    }

    String activity() {
        return visualizer.activity();
    }

    /** Um nó no alto da moldura, centrado no eixo da esfera (a pílula de estado da SPEC-010). */
    void topCenter(Node node) {
        if (topCenter != null) {
            getChildren().remove(topCenter);
        }
        topCenter = node;
        getChildren().add(node);
    }

    /**
     * Posiciona os controles pela moldura que o visualizador desenhou: eles
     * acompanham o console em qualquer tamanho de janela (SPEC-010 CA-8).
     */
    @Override protected void layoutChildren() {
        visualizer.resizeRelocate(0, 0, getWidth(), getHeight());
        javafx.geometry.Rectangle2D frame = visualizer.frameBounds();
        double scale = visualizer.scale();
        double right = frame.getMaxX() - VoiceVisualizer.BUTTONS_RIGHT * scale;
        // Os botões seguem a escala da moldura, como tudo o que está desenhado nela.
        double font = Math.max(10, Math.min(14, 15.5 * scale));
        String size = String.format(java.util.Locale.ROOT, "-fx-font-size: %.1fpx;", font);
        if (!size.equals(buttons.getStyle())) {
            buttons.setStyle(size);
        }
        double buttonsWidth = buttons.prefWidth(-1);
        double buttonsHeight = buttons.prefHeight(-1);
        double top = frame.getMinY() + VoiceVisualizer.BUTTONS_TOP * scale;
        buttons.resizeRelocate(right - buttonsWidth, top, buttonsWidth, buttonsHeight);

        double captionX = frame.getMinX() + VoiceVisualizer.METER_X * scale;
        double captionWidth = Math.max(120, right - captionX);
        caption.resizeRelocate(captionX, frame.getMinY() + (VoiceVisualizer.METER_Y + 36) * scale,
                captionWidth, caption.prefHeight(captionWidth));

        double cardWidth = Math.min(380, frame.getWidth() - 40);
        double cardTop = top + buttonsHeight + 10;
        double cardHeight = Math.min(card.prefHeight(cardWidth), frame.getMaxY() - cardTop - 10);
        card.resizeRelocate(right - cardWidth, cardTop, cardWidth, Math.max(0, cardHeight));

        double orb = 2 * 150 * scale;
        talk.setShape(new javafx.scene.shape.Circle(orb / 2));
        talk.resizeRelocate(frame.getMinX() + VoiceVisualizer.CX * scale - orb / 2,
                frame.getMinY() + VoiceVisualizer.CY * scale - orb / 2, orb, orb);

        if (topCenter != null) {
            double width = topCenter.prefWidth(-1);
            topCenter.resizeRelocate(frame.getMinX() + VoiceVisualizer.CX * scale - width / 2,
                    frame.getMinY() + 14 * scale, width, topCenter.prefHeight(width));
        }
    }

    private void play(Cue selected) {
        if (!active || getScene() == null || getScene().getWindow() == null
                || !getScene().getWindow().isShowing() || mute.isSelected() || volume.getValue() == 0) return;
        player.play(selected, new SoundSynthesizer.Settings(
                echo.isSelected(), reverb.isSelected(), soft.isSelected(), stereo.isSelected()));
        refreshStatus();
        timerRunning = true;
        timer.start();
    }

    void feedback(VoiceStatus before, VoiceStatus now) {
        if (!active || !automatic.isSelected() || before == null || now == null
                || !now.hostConnected() || !"ready".equals(now.engine()) || !"on".equals(now.capture())
                || java.util.Objects.equals(before.activity(), now.activity())) return;
        Cue signal = switch (now.activity()) {
            case "listening" -> Cue.LISTEN;
            case "thinking" -> Cue.THINK;
            case "speaking" -> Cue.RESPOND;
            default -> null;
        };
        if (signal != null) play(signal);
    }

    private void updateVolume() {
        player.setVolume(mute.isSelected() ? 0 : volume.getValue() / 100);
        refreshStatus();
        visualizer.tick();
    }

    private void refreshStatus() {
        String text = statusText();
        status.setText(text);
        // Em repouso ("pronto para testar") o console fica igual à referência.
        boolean resting = text.startsWith("●  PRONTO");
        caption.setText(resting ? "" : text);
        caption.setVisible(!resting);
        stop.setDisable(!player.playing());
        test.setDisable(mute.isSelected() || volume.getValue() == 0);
        test.setText(player.playing() ? "■  Parar som" : "▶  Testar som");
    }

    /** SPEC-008 v2 §3: diz onde o som vai tocar e, depois, se tocou de fato. */
    String statusText() {
        if (!player.error().isEmpty()) {
            return player.error();
        }
        if (mute.isSelected() || volume.getValue() == 0) {
            return "●  SOM SILENCIADO";
        }
        String output = outputName.isEmpty() ? "nenhuma saída encontrada" : outputName;
        if (player.playing()) {
            return "●  REPRODUZINDO EM " + output;
        }
        if (player.lastOutcome() instanceof SoundPlayer.Played played) {
            return "✓  Tocou em " + played.output() + " · "
                    + String.format(java.util.Locale.of("pt", "BR"), "%.2f s", played.seconds());
        }
        return "●  PRONTO PARA TESTAR · SAÍDA: " + output;
    }

    void pause() {
        player.stop();
        stopTimer();
        visualizer.tick();
        refreshStatus();
    }

    private void stopTimer() { timer.stop(); timerRunning = false; }
    boolean animating() { return timerRunning; }
    void close() { pause(); player.close(); }

    private static CheckBox option(String text, boolean selected) {
        CheckBox check = new CheckBox(text);
        check.setSelected(selected);
        return check;
    }
}
