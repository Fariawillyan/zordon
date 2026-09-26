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

import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;

/** A janela inteira em OPPRESSOR MODE (SPEC-036 CA-8). */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class OppressorThemeTest {

    private static final ShellActions NO_ACTIONS = new FakeShellActions() {
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

    private static ZordonShell shell(DesktopState state) {
        ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
        Stage stage = new Stage();
        stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
        stage.show();
        shell.applyCss();
        shell.layout();
        return shell;
    }

    private static Label banner(ZordonShell shell) {
        return (Label) shell.lookup(".oppressor-banner");
    }

    @AcceptanceCriteria("SPEC-036/CA-8")
    @Test
    void oTemaEAFaixaAcompanhamOEstadoDoNucleo() throws Exception {
        onFx(() -> {
            DesktopState state = new DesktopState();
            ZordonShell shell = shell(state);

            assertThat(shell.getStyleClass()).doesNotContain("oppressor");
            assertThat(banner(shell).isVisible()).isFalse();

            state.security().oppressorProperty().set(true);
            shell.applyCss();

            assertThat(shell.getStyleClass()).containsOnlyOnce("oppressor");
            assertThat(banner(shell).isVisible()).isTrue();
            assertThat(banner(shell).getText().replace(" ", "")).isEqualTo("OPPRESSORMODE");

            state.security().oppressorProperty().set(false);

            // Sair volta na hora: o tema não fica pendurado até a próxima tela.
            assertThat(shell.getStyleClass()).doesNotContain("oppressor");
            assertThat(banner(shell).isVisible()).isFalse();
            return null;
        });
    }

    @AcceptanceCriteria("SPEC-036/CA-8")
    @Test
    void aFaixaApareceEmQualquerTela() throws Exception {
        onFx(() -> {
            DesktopState state = new DesktopState();
            state.security().oppressorProperty().set(true);
            // Uma cena só para todos os destinos: a faixa mora na raiz, e um
            // Stage por destino derruba a JVM de teste antes do fim da suíte.
            ZordonShell shell = shell(state);

            for (Destination destination : Destination.values()) {
                state.select(destination);
                shell.applyCss();
                shell.layout();

                // O aviso não pode depender da aba: o modo vale para o processo todo.
                assertThat(banner(shell).isVisible()).as("faixa em %s", destination).isTrue();
                assertThat(shell.getStyleClass()).contains("oppressor");
            }
            return null;
        });
    }
}
