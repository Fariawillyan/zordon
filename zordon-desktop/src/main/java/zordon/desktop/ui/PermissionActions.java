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
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import zordon.desktop.shell.SecurityPresentation.Choice;
import zordon.desktop.shell.SecurityPresentation.Prompt;

/**
 * A linha de baixo do diálogo de permissão: a contagem regressiva e os botões.
 * Quando o tempo acaba, nega sozinho.
 */
final class PermissionActions extends HBox {

    private final Button once;
    private final Timeline countdown;
    private int remaining;

    PermissionActions(Prompt prompt, Consumer<Choice> decide) {
        super(8);
        setAlignment(Pos.CENTER_RIGHT);
        remaining = prompt.seconds();
        Label timer = new Label();
        timer.setId("permission-countdown");
        timer.getStyleClass().add("muted");
        timer.setText(remainingText());

        Button deny = new Button("Negar");
        deny.setId("permission-deny");
        deny.setDefaultButton(true);   // Enter nega (Segurança §2, regra 4)
        deny.setCancelButton(true);    // Esc também
        deny.setOnAction(event -> decide.accept(Choice.DENY));
        once = new Button(prompt.requiresCheck() ? "Autorizar esta ação" : "Autorizar");
        once.setId("permission-allow");
        once.setOnAction(event -> decide.accept(Choice.ONCE));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(timer, spacer, deny);
        if (prompt.offersSession()) {
            Button session = new Button("Autorizar nesta sessão");
            session.setId("permission-session");
            session.setOnAction(event -> decide.accept(Choice.SESSION));
            getChildren().add(session);
        }
        getChildren().add(once);

        countdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            remaining--;
            timer.setText(remainingText());
            if (remaining <= 0) {
                decide.accept(Choice.DENY);
            }
        }));
        countdown.setCycleCount(Math.max(1, prompt.seconds()));
    }

    /** Em RED, "Autorizar" só habilita depois de "Conferi os alvos". */
    void requireCheck(CheckBox checked) {
        once.disableProperty().bind(checked.selectedProperty().not());
    }

    void start() {
        countdown.play();
    }

    void stop() {
        countdown.stop();
    }

    private String remainingText() {
        return "Nega sozinho em " + Math.max(0, remaining) + " s";
    }
}
