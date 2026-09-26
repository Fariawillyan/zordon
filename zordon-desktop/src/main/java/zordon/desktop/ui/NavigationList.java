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

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import zordon.desktop.shell.Destination;

/** Os destinos da coluna de navegação, agrupados. A lista rola quando não cabe. */
final class NavigationList extends ScrollPane {

    static final Color ACTIVE = Color.web("#3DDCFF");
    private static final Color IDLE = Color.web("#6F8499");

    private final Map<Destination, Button> items = new EnumMap<>(Destination.class);

    NavigationList(Consumer<Destination> onSelect) {
        VBox destinations = new VBox(2);
        destinations.setAlignment(Pos.TOP_LEFT);
        for (Destination.Group group : Destination.Group.values()) {
            Label header = new Label(group.label());
            header.getStyleClass().add("nav-group");
            VBox.setMargin(header, new Insets(group == Destination.Group.values()[0] ? 0 : 10, 0, 2, 10));
            destinations.getChildren().add(header);
            Destination.inGroup(group).forEach(destination ->
                    destinations.getChildren().add(item(destination, onSelect)));
        }
        setContent(destinations);
        getStyleClass().add("nav-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        // A barra só aparece quando precisa: reservar a calha roubava 6 px de rótulo.
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
    }

    Button item(Destination destination) {
        return items.get(destination);
    }

    void highlight(Destination current) {
        items.forEach((destination, button) -> {
            boolean selected = destination == current;
            button.setGraphic(Icons.of(destination.icon(), 14, selected ? ACTIVE : IDLE));
            button.getStyleClass().remove("nav-selected");
            if (selected) {
                button.getStyleClass().add("nav-selected");
            }
        });
    }

    private Button item(Destination destination, Consumer<Destination> onSelect) {
        Button button = new Button(destination.label());
        button.setId("nav-" + destination.name().toLowerCase(java.util.Locale.ROOT));
        button.getStyleClass().add("nav-item");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setGraphicTextGap(7);
        button.setMinHeight(28);
        button.setPrefHeight(28);
        button.setAccessibleText(destination.label());
        button.setOnAction(event -> onSelect.accept(destination));
        items.put(destination, button);
        return button;
    }
}
