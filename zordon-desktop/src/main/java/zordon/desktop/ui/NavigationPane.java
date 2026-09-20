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
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
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
 * A coluna de navegação (SPEC-031): logo, todos os destinos agrupados, e no pé o
 * botão de status do núcleo.
 *
 * <p>Substituiu o trilho de quatro ícones da SPEC-010. A tela de Ajustes com abas
 * deixou de existir: o que era aba virou destino, e não há mais modo técnico
 * escondendo parte da navegação.
 */
@Spec("SPEC-031")
final class NavigationPane extends VBox {

    /**
     * Largura da coluna.
     *
     * <p>144 e não mais: o console de voz tem mínimo de ~575 px e a janela mínima
     * é de 720 (SPEC-010 CA-2) — sobram 145. Alargar a coluna aqui faz o console
     * transbordar na menor janela suportada; o jeito de alargar é encolher a coluna
     * para só ícones em janela estreita, que fica para depois.
     */
    static final double WIDTH = 144;

    private static final Color IDLE = Color.web("#6F8499");
    private static final Color ACTIVE = Color.web("#3DDCFF");

    private final Map<Destination, Button> items = new EnumMap<>(Destination.class);
    private final Rectangle core = new Rectangle(18, 2);
    private final Tooltip coreHint = new Tooltip();
    private final Button statusButton;

    NavigationPane(DesktopState state, Consumer<Destination> onSelect) {
        getStyleClass().add("navigation");
        setAlignment(Pos.TOP_LEFT);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        setPadding(new Insets(18, 4, 12, 4));

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
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(destinations);
        scroll.getStyleClass().add("nav-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        // A barra só aparece quando precisa: reservar a calha roubava 6 px de rótulo.
        scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        Region spacer = new Region();
        VBox.setVgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        core.getStyleClass().add("rail-core");
        Button coreBox = new Button("", core);
        coreBox.setId("core-status-button");
        coreBox.getStyleClass().add("core-status-button");
        coreBox.setMinSize(36, 32);
        coreBox.setPrefSize(36, 32);
        coreBox.setTooltip(coreHint);
        coreBox.setAccessibleHelp("Abre o status da conexão e o acesso ao diagnóstico.");
        this.statusButton = coreBox;
        ContextMenu statusMenu = statusMenu(state, onSelect);
        coreBox.setContextMenu(statusMenu);
        coreBox.setOnAction(event -> {
            if (statusMenu.isShowing()) statusMenu.hide();
            else statusMenu.show(coreBox, Side.RIGHT, 8, 0);
        });

        // A marca com a assinatura, como na referência do owner (SPEC-032).
        Label name = new Label("ZORDON");
        name.getStyleClass().add("nav-brand-name");
        Label tagline = new Label("SEMPRE AO SEU LADO");
        tagline.getStyleClass().add("nav-brand-tag");
        VBox words = new VBox(1, name, tagline);
        words.setAlignment(Pos.CENTER);
        VBox brand = new VBox(6, logo(), words);
        brand.setAlignment(Pos.CENTER);
        brand.setPadding(new Insets(0, 0, 18, 0));

        Label version = new Label("v0.1.0");
        version.getStyleClass().add("nav-footer-version");
        Label online = new Label();
        online.getStyleClass().add("nav-footer-version");
        online.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> switch (state.connectionProperty().get()) {
                    case ONLINE -> "Online";
                    case CONNECTING -> "Conectando";
                    case OFFLINE -> "Offline";
                }, state.connectionProperty()));
        HBox footer = new HBox(6, version, coreBox, online);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(8, 0, 0, 8));

        getChildren().addAll(brand, scroll, spacer, footer);

        state.destinationProperty().addListener((observable, before, now) -> highlight(now));
        highlight(state.destinationProperty().get());
        state.connectionProperty().addListener((observable, before, now) -> showCore(state));
        state.connectionDetailProperty().addListener((observable, before, now) -> showCore(state));
        state.reconnectionsProperty().addListener((observable, before, now) -> showCore(state));
        showCore(state);
    }

    private ContextMenu statusMenu(DesktopState state, Consumer<Destination> onSelect) {
        Label title = new Label("Status do Zordon");
        title.getStyleClass().add("core-status-title");
        Label connection = new Label();
        connection.setId("core-status-connection");
        connection.textProperty().bind(state.coreLabel());
        Label detail = new Label();
        detail.textProperty().bind(state.connectionDetailProperty());
        detail.setWrapText(true);
        detail.setMaxWidth(250);
        detail.visibleProperty().bind(detail.textProperty().isNotEmpty());
        detail.managedProperty().bind(detail.visibleProperty());
        Label drops = new Label();
        drops.textProperty().bind(state.reconnectionsProperty().asString("Quedas nesta sessão: %d"));
        VBox body = new VBox(7, title, connection, detail, drops);
        body.getStyleClass().add("core-status-body");
        CustomMenuItem info = new CustomMenuItem(body, false);
        MenuItem diagnostics = new MenuItem("Abrir diagnóstico");
        diagnostics.setId("core-status-diagnostics");
        diagnostics.setOnAction(event -> onSelect.accept(Destination.DIAGNOSTICS));
        ContextMenu menu = new ContextMenu(info, new SeparatorMenuItem(), diagnostics);
        menu.getStyleClass().add("core-status-menu");
        // Navegar por outro caminho também deve recolher o menu flutuante.
        state.destinationProperty().addListener((observable, before, now) -> menu.hide());
        return menu;
    }

    /** Texto do indicador do núcleo, também dito pelo leitor de tela (SPEC-010 CA-4). */
    String coreText() {
        return coreHint.getText();
    }

    /**
     * O traço que muda de cor com o estado do núcleo.
     *
     * <p>Desde que ele virou o conteúdo do botão de status, {@code lookup(".rail-core")}
     * só o encontra depois de o skin do botão existir — isto é, com a janela montada.
     * O acessório evita que um teste de regra precise de uma tela inteira.
     */
    javafx.scene.Node coreIndicator() {
        return core;
    }

    /** O item de um destino. Como a coluna rola, {@code lookup} depende do skin. */
    Button item(Destination destination) {
        return items.get(destination);
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

    private void highlight(Destination current) {
        items.forEach((destination, button) -> {
            boolean selected = destination == current;
            button.setGraphic(Icons.of(destination.icon(), 14, selected ? ACTIVE : IDLE));
            button.getStyleClass().remove("nav-selected");
            if (selected) {
                button.getStyleClass().add("nav-selected");
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
        statusButton.setAccessibleText(text);
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
