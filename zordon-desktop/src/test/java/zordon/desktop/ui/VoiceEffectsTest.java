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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.audio.SoundPlayer;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.VoiceStatus;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class VoiceEffectsTest {
    private static final ShellActions NO_ACTIONS = new ShellActions() {
        @Override public void send(String text, ComposerTarget target) {}
        @Override public void newConversation() {}
        @Override public void cancelTurn(String turnId) {}
        @Override public void refreshDiagnostics() {}
        @Override public void setVoiceMode(String mode) {}
        @Override public void loadVoiceDevices() {}
        @Override public void selectVoiceDevice(String deviceId) {}
        @Override public void testMicrophone() {}
        @Override public void startListening() {}
        @Override public void stopListening() {}
    };

    @BeforeAll static void toolkit() throws InterruptedException { FxTestSupport.start(); }

    @AcceptanceCriteria("SPEC-008/CA-1")
    @ParameterizedTest
    @ValueSource(ints = {800, 1200})
    void consoleFitsAndRendersAtBothWidths(int width) throws Exception {
        VoiceView view = onFx(() -> {
            DesktopState state = new DesktopState();
            state.voice(snapshot("idle", false));
            VoiceView built = new VoiceView(state, NO_ACTIONS);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(built, width, 940));
            stage.show();
            return built;
        });
        try {
            awaitLayout();
            onFx(() -> {
                var pane = view.lookup("#voice-effects");
                assertThat(pane.getBoundsInParent().getWidth()).isLessThanOrEqualTo(width);
                assertThat(pane.lookupAll(".button")).isNotEmpty();
                assertThat(view.lookup("#voice-volume").getAccessibleText()).contains("Volume");
                // SPEC-010: no console ficam só "Testar som" e "Ajustes"; os modos foram para os Ajustes.
                assertThat(view.lookupAll(".console-button")).hasSize(2).allSatisfy(button ->
                        assertThat(button.localToScene(button.getBoundsInLocal()).getMaxX()).isLessThanOrEqualTo(width));
                FxTestSupport.save(view.getScene(), Path.of("build/ui-snapshots/voice-console-" + width + ".png"));
                ((ToggleButton) view.lookup("#voice-settings-toggle")).fire();
                return null;
            });
            awaitLayout();
            onFx(() -> {
                assertThat(view.lookup("#voice-settings").isVisible()).isTrue();
                assertThat(view.lookup("#voice-settings").getBoundsInParent().getHeight()).isGreaterThan(100);
                FxTestSupport.save(view.getScene(), Path.of("build/ui-snapshots/voice-settings-" + width + ".png"));
                return null;
            });
        } finally { onFx(() -> { view.close(); ((Stage) view.getScene().getWindow()).close(); return null; }); }
    }

    private static void awaitLayout() throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        onFx(() -> {
            new javafx.animation.AnimationTimer() {
                int frames;
                @Override public void handle(long now) {
                    if (++frames == 3) { stop(); rendered.countDown(); }
                }
            }.start();
            return null;
        });
        assertThat(rendered.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @AcceptanceCriteria("SPEC-008/CA-6")
    @Test
    void localTestWorksWithoutHostAndNeverClaimsMicrophoneCapture() throws Exception {
        onFx(() -> {
            DesktopState state = new DesktopState();
            VoiceView view = new VoiceView(state, NO_ACTIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(view, 800, 900));
                stage.show();
                assertThat(((Button) view.lookup("#voice-test")).isDisabled()).isFalse();
                Slider volume = (Slider) view.lookup("#voice-volume");
                volume.setValue(62);
                state.voice(snapshot("idle", false));
                assertThat(((Slider) view.lookup("#voice-volume")).getValue()).isEqualTo(62);
                // Os detalhes do microfone moraram num painel recolhido da Voz; desde a
                // SPEC-010 eles estão nos Ajustes. A garantia é a mesma.
                VoiceSettingsView details = new VoiceSettingsView(state, NO_ACTIONS);
                assertThat(details.getContent().lookupAll(".label").stream().map(node -> ((Label) node).getText()))
                        .anyMatch(text -> text.contains("zordon-host"))
                        .anyMatch(text -> text.contains("Não instalado"))
                        .anyMatch(text -> text.contains("não controla o microfone"));
            } finally { view.close(); stage.close(); }
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-008/CA-7")
    @Test
    void feedbackRequiresOptInConfirmedCaptureAndAnActivityTransition() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SoundPlayer player = new SoundPlayer(() -> {
            opens.incrementAndGet();
            opened.countDown();
            return output(release);
        });
        VoiceEffectsPane pane = onFx(() -> {
            VoiceEffectsPane built = new VoiceEffectsPane(player);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(new StackPane(built), 800, 760));
            stage.show();
            built.active(true);
            VoiceStatus idle = VoiceStatus.from(snapshot("idle", true));
            VoiceStatus listening = VoiceStatus.from(snapshot("listening", true));
            built.feedback(idle, listening);
            assertThat(player.playing()).isFalse();
            built.lookupAll(".check-box").stream().map(node -> (CheckBox) node)
                    .filter(check -> check.getText().startsWith("Feedback")).findFirst().orElseThrow().setSelected(true);
            built.feedback(null, listening);
            built.feedback(idle, VoiceStatus.from(snapshot("listening", false)));
            built.feedback(idle, idle);
            assertThat(player.playing()).isFalse();
            built.feedback(idle, listening);
            return built;
        });
        try {
            assertThat(opened.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(opens.get()).isEqualTo(1);
            awaitLayout();
            onFx(() -> {
                FxTestSupport.save(pane.getScene(), Path.of("build/ui-snapshots/voice-playing-800.png"));
                assertThat(pane.animating()).isTrue();
                pane.active(false);
                assertThat(player.playing()).isFalse();
                assertThat(pane.animating()).isFalse();
                return null;
            });
        } finally {
            onFx(() -> { pane.close(); ((Stage) pane.getScene().getWindow()).close(); return null; });
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-5")
    @Test
    void hidingWindowStopsOutputAndTimerAndMuteDisablesTest() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        SoundPlayer player = new SoundPlayer(() -> { opened.countDown(); return output(closed); });
        VoiceEffectsPane pane = onFx(() -> {
            VoiceEffectsPane built = new VoiceEffectsPane(player);
            Stage stage = new Stage();
            stage.setScene(FxTestSupport.styledScene(new StackPane(built), 800, 760));
            stage.show();
            built.active(true);
            ((Button) built.lookup("#voice-test")).fire();
            return built;
        });
        try {
            assertThat(opened.await(2, TimeUnit.SECONDS)).isTrue();
            onFx(() -> {
                ((Stage) pane.getScene().getWindow()).hide();
                assertThat(player.playing()).isFalse();
                assertThat(pane.animating()).isFalse();
                ((ToggleButton) pane.lookup("#voice-mute")).fire();
                assertThat(((Button) pane.lookup("#voice-test")).isDisabled()).isTrue();
                assertThat(player.volume()).isZero();
                return null;
            });
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { onFx(() -> { pane.close(); return null; }); }
    }

    private static Map<String, Object> snapshot(String activity, boolean ready) {
        return Map.of("mode", "off", "effective", ready ? "wake" : "off", "activity", activity,
                "capture", Map.of("state", ready ? "on" : "unknown"),
                "host", Map.of("connected", ready), "engine", Map.of("state", ready ? "ready" : "absent"));
    }

    private static SoundPlayer.Output output(CountDownLatch closed) {
        return new SoundPlayer.Output() {
            @Override public void start() {}
            @Override public int write(byte[] data, int offset, int length) { return length; }
            @Override public long framePosition() { return 12000; }
            @Override public void drain() {
                try { closed.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }
            @Override public void close() { closed.countDown(); }
        };
    }
}
