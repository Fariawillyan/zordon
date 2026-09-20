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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.SecurityPresentation;

/**
 * O banner dos avisos do Zordon (SPEC-015, Comunicação §3): WARNING e acima,
 * o mais antigo primeiro, até o usuário dizer "Entendi".
 */
@Spec("SPEC-015")
final class NotificationBar extends VBox {

    private final Label text = new Label();
    private final Label more = new Label();
    private final Label severity = new Label();
    private final Label title = new Label();
    private Map<String, Object> current;
    private boolean readingDetails;

    NotificationBar(DesktopState state, ShellActions actions) {
        this(state, actions, () -> state.select(zordon.desktop.shell.Destination.SECURITY));
    }

    NotificationBar(DesktopState state, ShellActions actions, Runnable openNotifications) {
        setId("notification-bar");
        getStyleClass().add("notification-bar");
        setSpacing(7);
        setMinWidth(0);
        setPrefWidth(460);
        setMaxWidth(460);
        // StackPane estica os filhos por padrão, mesmo alinhados ao topo.
        setMaxHeight(Region.USE_PREF_SIZE);
        severity.getStyleClass().add("notification-severity");
        more.getStyleClass().add("notification-count");
        more.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, severity, spacer, more);
        header.setAlignment(Pos.CENTER_LEFT);
        title.getStyleClass().add("notification-title");
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        text.getStyleClass().add("notification-summary");
        text.setWrapText(true);
        text.setMinHeight(0);
        text.setMaxHeight(36);
        Button details = new Button("Ver avisos");
        details.setId("notification-details");
        details.getStyleClass().add("button-tertiary");
        details.setOnAction(event -> {
            readingDetails = true;
            render(state);
            openNotifications.run();
        });
        Button ok = new Button("Entendi");
        ok.setId("notification-ack");
        ok.getStyleClass().add("button-secondary");
        ok.setMinWidth(Region.USE_PREF_SIZE);
        ok.setOnAction(event -> {
            if (current != null) {
                String id = String.valueOf(current.get("messageId"));
                state.acknowledged(id);
                actions.acknowledge(id);
            }
        });
        HBox footer = new HBox(8, details, ok);
        footer.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(header, title, text, footer);
        state.notifications().addListener((ListChangeListener<Map<String, Object>>) change -> render(state));
        state.destinationProperty().addListener((observable, before, now) -> {
            if (now != zordon.desktop.shell.Destination.SECURITY) readingDetails = false;
            render(state);
        });
        render(state);
    }

    private void render(DesktopState state) {
        current = state.notifications().stream()
                .filter(message -> SecurityPresentation.banner(String.valueOf(message.get("severity"))))
                .findFirst().orElse(null);
        boolean shown = current != null && !readingDetails;
        setVisible(shown);
        setManaged(shown);
        if (!shown) {
            return;
        }
        getStyleClass().removeAll("severity-warning", "severity-high", "severity-critical");
        getStyleClass().add("severity-" + current.get("severity"));
        String level = String.valueOf(current.get("severity"));
        severity.setText(switch (level) {
            case "critical" -> "CRÍTICO";
            case "high" -> "ALTA PRIORIDADE";
            default -> "ATENÇÃO";
        });
        title.setText(String.valueOf(current.getOrDefault("title", "Aviso do Zordon")));
        title.setTooltip(new Tooltip(title.getText()));
        text.setText(String.valueOf(current.getOrDefault("actionTaken", "")));
        text.setTooltip(new Tooltip(text.getText()));
        text.setVisible(!text.getText().isBlank());
        text.setManaged(text.isVisible());
        long others = state.notifications().stream()
                .filter(message -> SecurityPresentation.banner(String.valueOf(message.get("severity")))).count() - 1;
        more.setText(others > 0 ? "+" + others + " pendente" + (others == 1 ? "" : "s") : "");
        more.setVisible(others > 0);
        more.setManaged(others > 0);
    }

    String text() {
        return current == null ? "" : SecurityPresentation.bannerText(current);
    }
}
