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

import java.util.Map;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.SecurityPresentation;

/**
 * O banner dos avisos do Zordon (SPEC-015, Comunicação §3): WARNING e acima,
 * o mais antigo primeiro, até o usuário dizer "Entendi".
 */
@Spec("SPEC-015")
final class NotificationBar extends HBox {

    private final Label text = new Label();
    private final Label more = new Label();
    private Map<String, Object> current;

    NotificationBar(DesktopState state, ShellActions actions) {
        setId("notification-bar");
        getStyleClass().add("notification-bar");
        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(640);
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        more.getStyleClass().add("muted");
        Button ok = new Button("Entendi");
        ok.setId("notification-ack");
        ok.setOnAction(event -> {
            if (current != null) {
                String id = String.valueOf(current.get("messageId"));
                state.acknowledged(id);
                actions.acknowledge(id);
            }
        });
        getChildren().addAll(text, more, ok);
        state.notifications().addListener((ListChangeListener<Map<String, Object>>) change -> render(state));
        render(state);
    }

    private void render(DesktopState state) {
        current = state.notifications().stream()
                .filter(message -> SecurityPresentation.banner(String.valueOf(message.get("severity"))))
                .findFirst().orElse(null);
        boolean shown = current != null;
        setVisible(shown);
        setManaged(shown);
        if (!shown) {
            return;
        }
        getStyleClass().removeAll("severity-warning", "severity-high", "severity-critical");
        getStyleClass().add("severity-" + current.get("severity"));
        text.setText(SecurityPresentation.bannerText(current));
        long others = state.notifications().stream()
                .filter(message -> SecurityPresentation.banner(String.valueOf(message.get("severity")))).count() - 1;
        more.setText(others > 0 ? "+" + others : "");
    }

    String text() {
        return text.getText();
    }
}
