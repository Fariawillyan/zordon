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
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import zordon.api.trace.Spec;
import zordon.desktop.audio.SoundPlayer;
import zordon.desktop.audio.SoundSynthesizer.Cue;
import zordon.desktop.shell.VoiceStatus;

/** Local feedback controls, deliberately independent of microphone availability. */
@Spec("SPEC-008")
final class VoiceEffectsPane extends Pane {
    private final SoundPlayer player;
    /** A saída padrão, lida uma vez: consultar o sistema a 30 fps custaria caro. */
    private final String outputName;
    private final VoiceVisualizer visualizer;
    /** Resultado do último som, sob o medidor; some em repouso, como na referência. */
    private final Label caption = new Label();
    private final VoiceConsoleButtons buttons;
    private final VoiceEffectsSettings card;
    private Node topCenter;
    private final TalkOrb talk = new TalkOrb();
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
        buttons = new VoiceConsoleButtons(this::testOrStop);
        card = new VoiceEffectsSettings(this::pause, this::updateVolume, visualizer::reducedMotion);
        card.visibleProperty().bind(buttons.settingsOpen());
        caption.getStyleClass().add("console-caption");
        caption.setId("voice-output-caption");
        caption.setWrapText(true);
        getChildren().addAll(visualizer, talk, caption, buttons, card);
        ShowingGuard.install(this, this::pause);
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

    void micLevel(double[] levels) {
        visualizer.micLevel(levels);
    }

    /** OPPRESSOR MODE no núcleo visual (SPEC-036 CA-8). */
    void oppressor(boolean value) {
        visualizer.oppressor(value);
    }

    String activity() {
        return visualizer.activity();
    }

    /** Põe o seletor de modo no alto do painel de Ajustes. */
    void modeControl(Node control) {
        card.modeControl(control);
    }

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
        talk.place(frame.getMinX() + VoiceVisualizer.CX * scale, frame.getMinY() + VoiceVisualizer.CY * scale, orb);

        if (topCenter != null) {
            double width = topCenter.prefWidth(-1);
            topCenter.resizeRelocate(frame.getMinX() + VoiceVisualizer.CX * scale - width / 2,
                    frame.getMinY() + 14 * scale, width, topCenter.prefHeight(width));
        }
    }

    private void testOrStop() {
        if (player.playing()) pause();
        else play(card.cue());
    }

    private void play(Cue selected) {
        if (!active || getScene() == null || getScene().getWindow() == null
                || !getScene().getWindow().isShowing() || card.silenced()) return;
        player.play(selected, card.effects());
        refreshStatus();
        timerRunning = true;
        timer.start();
    }

    void feedback(VoiceStatus before, VoiceStatus now) {
        if (!active || !card.automaticFeedback() || before == null || now == null
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
        player.setVolume(card.level());
        refreshStatus();
        visualizer.tick();
    }

    private void refreshStatus() {
        String text = statusText();
        card.show(text, player.playing());
        // Em repouso ("pronto para testar") o console fica igual à referência.
        boolean resting = text.startsWith("●  PRONTO");
        caption.setText(resting ? "" : text);
        caption.setVisible(!resting);
        buttons.refresh(player.playing(), card.silenced());
    }

    /** SPEC-008 v2 §3: diz onde o som vai tocar e, depois, se tocou de fato. */
    String statusText() {
        if (!player.error().isEmpty()) {
            return player.error();
        }
        if (card.silenced()) {
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
}
