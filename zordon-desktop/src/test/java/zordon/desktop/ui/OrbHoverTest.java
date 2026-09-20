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

import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;

/**
 * O orbe de falar não pode pintar nada por cima da marca (SPEC-032 CA-1).
 *
 * <p>O tema padrão do JavaFX dá fundo a botão em {@code :hover} e {@code :armed};
 * com a forma de círculo do orbe, isso virava um disco escuro sobre o Zordon.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class OrbHoverTest {

    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass ARMED = PseudoClass.getPseudoClass("armed");
    private static final PseudoClass PRESSED = PseudoClass.getPseudoClass("pressed");

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    private static final ShellActions NO_ACTIONS = new ShellActions() {
        @Override
        public void send(String text, ComposerTarget target) {}

        @Override
        public void newConversation() {}

        @Override
        public void cancelTurn(String turnId) {}

        @Override
        public void refreshDiagnostics() {}

        @Override
        public void setVoiceMode(String mode) {}

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

    @AcceptanceCriteria("SPEC-032/CA-1")
    @Test
    void oOrbeNaoEscureceAMarcaEmNenhumEstado() throws Exception {
        onFx(() -> {
            DesktopState state = new DesktopState();
            state.online("0.1.0");
            ZordonShell shell = new ZordonShell(state, NO_ACTIONS);
            Stage stage = new Stage();
            try {
                stage.setScene(FxTestSupport.styledScene(shell, 960, 720));
                stage.show();
                shell.applyCss();
                shell.layout();
                Button orb = (Button) shell.lookup("#voice-talk");
                assertThat(orb).isNotNull();

                for (PseudoClass estado : new PseudoClass[] {HOVER, ARMED, PRESSED}) {
                    orb.pseudoClassStateChanged(estado, true);
                    shell.applyCss();
                    shell.layout();
                    assertThat(opaco(orb)).as("o orbe pinta fundo em :%s", estado.getPseudoClassName()).isFalse();
                    orb.pseudoClassStateChanged(estado, false);
                }
            } finally {
                shell.close();
                stage.close();
            }
            return null;
        });
    }

    /** Se alguma camada do fundo tem cor visível, ela cobre o desenho. */
    private static boolean opaco(Button orb) {
        if (orb.getBackground() == null) {
            return false;
        }
        for (BackgroundFill fill : orb.getBackground().getFills()) {
            if (fill.getFill() instanceof Color color && color.getOpacity() > 0.01) {
                return true;
            }
            if (!(fill.getFill() instanceof Color)) {
                return true;   // gradiente também cobre
            }
        }
        return false;
    }
}
