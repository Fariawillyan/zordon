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

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

/** Os botões do alto do console de voz: testar o som e abrir os Ajustes. */
final class VoiceConsoleButtons extends HBox {

    private final Button test = new Button("▶  Testar som");
    private final ToggleButton settings = new ToggleButton("Ajustes", Icons.of("settings", 14, Color.web("#C7D6E2")));

    VoiceConsoleButtons(Runnable onTest) {
        super(14);
        test.getStyleClass().addAll("console-button", "console-primary");
        test.setId("voice-test");
        test.setOnAction(event -> onTest.run());
        settings.getStyleClass().add("console-button");
        settings.setId("voice-settings-toggle");
        getChildren().addAll(test, settings);
    }

    /** Se os Ajustes estão abertos. */
    BooleanProperty settingsOpen() {
        return settings.selectedProperty();
    }

    /** Tocando, o teste vira "parar"; silenciado, não há o que testar. */
    void refresh(boolean playing, boolean silenced) {
        test.setDisable(silenced);
        test.setText(playing ? "■  Parar som" : "▶  Testar som");
    }
}
