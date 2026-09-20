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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;

/** Escolher quando o Zordon escuta, de onde se está olhando (SPEC-033). */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class VoiceModeTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    private final List<String> pedidos = new ArrayList<>();

    private final ShellActions actions = new ShellActions() {
        @Override
        public void send(String text, ComposerTarget target) {}

        @Override
        public void newConversation() {}

        @Override
        public void cancelTurn(String turnId) {}

        @Override
        public void refreshDiagnostics() {}

        @Override
        public void setVoiceMode(String mode) {
            pedidos.add(mode);
        }

        @Override
        public void loadVoiceDevices() {}

        @Override
        public void selectVoiceDevice(String deviceId) {}

        @Override
        public void testMicrophone() {}

        @Override
        public void startListening() {}

        @Override
        public void stopListening() {}
    };

    @AcceptanceCriteria("SPEC-033/CA-1")
    @Test
    void osQuatroModosAparecemComOQueCadaUmFaz() throws Exception {
        onFx(() -> {
            VoiceModePicker picker = new VoiceModePicker(comVoz("wake"), actions, true);

            assertThat(VoiceModePicker.MODES).containsExactly("off", "wake", "push", "open");
            for (String mode : VoiceModePicker.MODES) {
                assertThat(picker.lookup("#mode-" + mode)).as("falta o modo %s", mode).isNotNull();
                assertThat(VoiceModePicker.explain(mode)).as("o modo %s não se explica", mode).isNotBlank();
            }
            // O modo de 5 minutos precisa dizer que são 5 minutos.
            assertThat(VoiceModePicker.explain("open")).contains("5 minutos");
            assertThat(VoiceModePicker.explain("off")).contains("Nada é capturado");
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-033/CA-2")
    @Test
    void clicarPedeOModoAoNucleoEOJaSelecionadoContinuaSelecionado() throws Exception {
        onFx(() -> {
            VoiceModePicker picker = new VoiceModePicker(comVoz("wake"), actions, true);

            ToggleButton aberta = (ToggleButton) picker.lookup("#mode-open");
            aberta.fire();
            assertThat(pedidos).containsExactly("open");

            ToggleButton atual = (ToggleButton) picker.lookup("#mode-wake");
            assertThat(atual.isSelected()).as("o modo em vigor começa marcado").isTrue();
            atual.fire();
            assertThat(atual.isSelected()).as("clicar no selecionado não desmarca").isTrue();
            assertThat(pedidos).containsExactly("open", "wake");
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-033/CA-1")
    @Test
    void semRespostaDoNucleoOsBotoesNaoEnganam() throws Exception {
        onFx(() -> {
            VoiceModePicker picker = new VoiceModePicker(new DesktopState(), actions, true);

            assertThat(((ToggleButton) picker.lookup("#mode-wake")).isDisabled()).isTrue();
            return null;
        });
    }

    private static DesktopState comVoz(String mode) {
        DesktopState state = new DesktopState();
        state.online("0.1.0");
        state.voice(Map.of("mode", mode, "effective", mode, "activity", "idle",
                "capture", Map.of("state", "on", "requested", true, "confirmedAt", "2026-09-20T10:00:00Z"),
                "host", Map.of("connected", true),
                "engine", Map.of("state", "ready")));
        return state;
    }
}
