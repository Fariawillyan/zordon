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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;

/** A esfera e o Ctrl+Espaço falam com o Zordon (SPEC-011 CA-7). */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class VoiceTalkTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private final ShellActions actions = new ShellActions() {
        @Override public void send(String text, ComposerTarget target) {}
        @Override public void newConversation() {}
        @Override public void cancelTurn(String turnId) {}
        @Override public void refreshDiagnostics() {}
        @Override public void setVoiceMode(String mode) {}
        @Override public void loadVoiceDevices() {}
        @Override public void selectVoiceDevice(String deviceId) {}
        @Override public void testMicrophone() {}
        @Override public void startListening() { calls.add("start"); }
        @Override public void stopListening() { calls.add("stop"); }
    };

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    @AcceptanceCriteria("SPEC-011/CA-7")
    @Test
    void clicarNaEsferaComecaEClicarDeNovoDuranteAEscutaEncerra() throws Exception {
        onFx(() -> {
            DesktopState state = new DesktopState();
            state.online("0.1.0");
            ZordonShell shell = new ZordonShell(state, actions);
            javafx.scene.control.Button orb = (javafx.scene.control.Button) shell.lookup("#voice-talk");
            assertThat(orb.getAccessibleText()).isEqualTo("Falar com o Zordon");

            orb.fire();
            state.voice(Map.of("mode", "off", "effective", "off", "activity", "listening",
                    "capture", Map.of("state", "on"), "host", Map.of("connected", true),
                    "engine", Map.of("state", "ready")));
            orb.fire();

            assertThat(calls).containsExactly("start", "stop");
            shell.close();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-011/CA-7")
    @Test
    void ctrlEspacoFalaDeQualquerTela() throws Exception {
        onFx(() -> {
            DesktopState state = new DesktopState();
            ZordonShell shell = new ZordonShell(state, actions);
            javafx.scene.Scene scene = FxTestSupport.styledScene(shell, 960, 720);
            state.select(zordon.desktop.shell.Destination.SETTINGS);

            Runnable talk = scene.getAccelerators().get(new javafx.scene.input.KeyCodeCombination(
                    javafx.scene.input.KeyCode.SPACE, javafx.scene.input.KeyCombination.CONTROL_DOWN));
            talk.run();

            assertThat(calls).containsExactly("start");
            shell.close();
            return null;
        });
    }
}
