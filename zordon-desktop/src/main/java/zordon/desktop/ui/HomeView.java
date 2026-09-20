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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;
import zordon.zwp.CoreConnection;

/**
 * Início: a tela de retomada. Pendências reais primeiro, depois o que está em
 * andamento, a última conversa e até quatro atalhos. Sem lista fictícia de agentes
 * nem promessa de proteção ([Layout §4](../../../../../../../docs/specs/ui/desktop-layout.md#4-início-e-chat)).
 */
final class HomeView extends ScrollPane {

    private final VBox content = new VBox(20);
    private final DesktopState state;
    private final Consumer<Destination> navigate;
    private final Runnable newConversation;
    private String lastConversation = "";

    HomeView(DesktopState state, Consumer<Destination> navigate, Runnable newConversation) {
        this.state = state;
        this.navigate = navigate;
        this.newConversation = newConversation;
        getStyleClass().add("page");
        setFitToWidth(true);
        content.setPadding(new Insets(20, 24, 24, 24));
        content.setMaxWidth(880);
        setContent(content);

        state.connectionProperty().addListener((observable, before, now) -> render());
        state.diagnosticsProperty().addListener((observable, before, now) -> render());
        state.turnRunningProperty().addListener((observable, before, now) -> render());
        state.voiceProperty().addListener((observable, before, now) -> render());
        render();
    }

    /** O início da última conversa, para retomar sem duplicar o histórico do Chat. */
    void setLastConversation(String preview) {
        lastConversation = preview == null ? "" : preview;
        render();
    }

    private void render() {
        Label title = new Label("Painel");
        title.getStyleClass().add("page-title");
        content.getChildren().setAll(title);

        pending().ifPresent(content.getChildren()::add);
        if (state.turnRunningProperty().get()) {
            content.getChildren().add(section("Em andamento", text("Um turno está sendo respondido.")));
        }
        content.getChildren().add(lastConversation.isBlank() ? firstSteps() : resume());
        content.getChildren().add(section("Atalhos", shortcuts()));
        content.getChildren().add(section("Destinos", destinations()));
    }

    /**
     * O que não cabe no trilho (SPEC-010 CA-5): Logs e Diagnóstico abrem daqui, e
     * os destinos futuros aparecem indisponíveis, com o marco (SPEC-005 CA-2).
     */
    private Node destinations() {
        FlowPane tiles = new FlowPane(10, 10);
        for (Destination destination : Destination.values()) {
            if (Destination.RAIL.contains(destination)) {
                continue;
            }
            Button tile = new Button(destination.label(), Icons.of(destination.icon(), 18,
                    Color.web(destination.isAvailable() ? "#3DDCFF" : "#60758E")));
            tile.getStyleClass().add("tile");
            tile.setId("tile-" + destination.name().toLowerCase(java.util.Locale.ROOT));
            if (!destination.isAvailable()) {
                tile.getStyleClass().add("tile-unavailable");
                tile.setText(destination.label() + "  ·  " + destination.badge());
                tile.setTooltip(new javafx.scene.control.Tooltip(destination.unavailableReason()));
                tile.setAccessibleText(destination.label() + ", indisponível. " + destination.unavailableReason());
            }
            tile.setOnAction(event -> navigate.accept(destination));
            tiles.getChildren().add(tile);
        }
        return tiles;
    }

    private java.util.Optional<Node> pending() {
        if (state.connectionProperty().get() == CoreConnection.State.OFFLINE) {
            return java.util.Optional.of(section("Pendente", warning(
                    "O núcleo está offline. A janela reconecta sozinha quando ele voltar.")));
        }
        return state.providerWarning().map(warning -> section("Pendente", warning(warning)));
    }

    private Node resume() {
        Button open = new Button("Abrir conversa");
        open.getStyleClass().add("button-secondary");
        open.setOnAction(event -> navigate.accept(Destination.CHAT));
        Label preview = text(lastConversation);
        preview.getStyleClass().add("quote");
        return section("Última conversa", new VBox(12, preview, open));
    }

    /** Primeiro uso: o que falta para funcionar, sem prometer o que não está pronto. */
    private Node firstSteps() {
        boolean connected = state.connectionProperty().get() == CoreConnection.State.ONLINE;
        boolean modelReady = state.diagnosticsProperty().get() != null && state.providerWarning().isEmpty();
        return section("Primeiros passos", new VBox(8,
                step(connected, "Núcleo conectado", "o serviço no WSL responde"),
                step(modelReady, "Modelo configurado", "em ~/.zordon/config.toml — veja Primeiros passos, passo 4"),
                step(state.voiceProperty().get() != null && state.voiceProperty().get().hostConnected(),
                        "Host do Windows", "packaging/windows/install-host.sh — controla o microfone")));
    }

    private Node shortcuts() {
        FlowPane flow = new FlowPane(12, 12);
        flow.getChildren().addAll(
                shortcut("Nova conversa", "plus", newConversation),
                shortcut("Ver logs", "list", () -> navigate.accept(Destination.LOGS)),
                shortcut("Diagnóstico", "pulse", () -> navigate.accept(Destination.DIAGNOSTICS)));
        return flow;
    }

    private static Button shortcut(String label, String icon, Runnable action) {
        Button button = new Button(label, Icons.of(icon, 18, Color.web("#3DDCFF")));
        button.getStyleClass().add("shortcut");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Node step(boolean done, String title, String detail) {
        Label mark = new Label(done ? "✓" : "○");
        mark.getStyleClass().add(done ? "step-done" : "step-pending");
        Label name = new Label(title);
        name.getStyleClass().add("row-value");
        Label why = new Label(detail);
        why.getStyleClass().add("muted");
        why.setWrapText(true);
        var row = new javafx.scene.layout.HBox(10, mark, new VBox(2, name, why));
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    static VBox section(String title, Node body) {
        Label label = new Label(title);
        label.getStyleClass().add("card-title");
        VBox card = new VBox(12, label, body);
        card.getStyleClass().add("card");
        return card;
    }

    private static Label text(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add("body-text");
        return label;
    }

    private static Label warning(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add("warning-text");
        return label;
    }
}
