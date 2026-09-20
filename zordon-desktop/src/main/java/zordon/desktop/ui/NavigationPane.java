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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineJoin;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;
import zordon.zwp.CoreConnection;

/**
 * O trilho da SPEC-010: logo, os quatro destinos e, no pé, o indicador do
 * núcleo. Largura fixa de 56 px em qualquer janela.
 */
@Spec("SPEC-010")
final class NavigationPane extends VBox {

    static final double WIDTH = 56;

    private static final Color IDLE = Color.web("#6F8499");
    private static final Color ACTIVE = Color.web("#3DDCFF");

    private final Map<Destination, Button> items = new EnumMap<>(Destination.class);
    private final Rectangle core = new Rectangle(18, 2);
    private final Tooltip coreHint = new Tooltip();

    NavigationPane(DesktopState state, Consumer<Destination> onSelect) {
        getStyleClass().add("rail");
        setAlignment(Pos.TOP_CENTER);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        setPadding(new Insets(28, 0, 22, 0));

        VBox destinations = new VBox(10);
        destinations.setAlignment(Pos.TOP_CENTER);
        for (Destination destination : Destination.RAIL) {
            destinations.getChildren().add(item(destination, onSelect));
        }
        Region spacer = new Region();
        VBox.setVgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        core.getStyleClass().add("rail-core");
        StackPane coreBox = new StackPane(core);
        coreBox.setMinHeight(20);
        coreBox.setAccessibleText("Estado do núcleo");
        Tooltip.install(coreBox, coreHint);

        StackPane brand = new StackPane(logo());
        brand.setPadding(new Insets(0, 0, 48, 0));
        getChildren().addAll(brand, destinations, spacer, coreBox);

        // O Painel (Logs, Diagnóstico) só existe no modo técnico (SPEC-012 CA-8).
        Button panel = items.get(Destination.HOME);
        panel.visibleProperty().bind(state.technicalModeProperty());
        panel.managedProperty().bind(state.technicalModeProperty());
        state.destinationProperty().addListener((observable, before, now) -> highlight(now));
        highlight(state.destinationProperty().get());
        state.connectionProperty().addListener((observable, before, now) -> showCore(state));
        state.connectionDetailProperty().addListener((observable, before, now) -> showCore(state));
        state.reconnectionsProperty().addListener((observable, before, now) -> showCore(state));
        showCore(state);
    }

    /** Texto do indicador do núcleo, também dito pelo leitor de tela (SPEC-010 CA-4). */
    String coreText() {
        return coreHint.getText();
    }

    private Button item(Destination destination, Consumer<Destination> onSelect) {
        Button button = new Button();
        button.getStyleClass().add("rail-item");
        button.setMinSize(WIDTH, 36);
        button.setPrefSize(WIDTH, 36);
        button.setAccessibleText(destination.label());
        button.setTooltip(new Tooltip(destination.label()));
        button.setOnAction(event -> onSelect.accept(destination));
        items.put(destination, button);
        return button;
    }

    private void highlight(Destination current) {
        Destination owner = current.railOwner();
        items.forEach((destination, button) -> {
            boolean selected = destination == owner;
            button.setGraphic(Icons.of(destination.icon(), 20, selected ? ACTIVE : IDLE));
            button.getStyleClass().remove("rail-selected");
            if (selected) {
                button.getStyleClass().add("rail-selected");
            }
        });
    }

    private void showCore(DesktopState state) {
        CoreConnection.State connection = state.connectionProperty().get();
        core.getStyleClass().removeAll("core-online", "core-connecting", "core-offline");
        core.getStyleClass().add("core-" + connection.name().toLowerCase(java.util.Locale.ROOT));
        String text = switch (connection) {
            case ONLINE -> "Núcleo conectado · " + state.connectionDetailProperty().get();
            case CONNECTING -> "Conectando ao núcleo…";
            case OFFLINE -> "Núcleo offline — " + state.connectionDetailProperty().get()
                    + " · quedas nesta sessão: " + state.reconnectionsProperty().get();
        };
        coreHint.setText(text);
        getChildren().getLast().setAccessibleText(text);
    }

    /** O símbolo da imagem: dois triângulos, sem bitmap. */
    private static StackPane logo() {
        Polygon outer = new Polygon(12, 1, 23, 20, 1, 20);
        outer.setFill(Color.TRANSPARENT);
        outer.setStroke(ACTIVE);
        outer.setStrokeWidth(2.2);
        outer.setStrokeLineJoin(StrokeLineJoin.MITER);
        StackPane mark = new StackPane(outer);
        mark.setAccessibleText("Zordon");
        return mark;
    }
}
