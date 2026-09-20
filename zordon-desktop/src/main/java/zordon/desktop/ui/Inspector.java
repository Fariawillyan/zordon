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

import java.time.Duration;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Diagnostics;
import zordon.desktop.shell.SessionUsage;
import zordon.desktop.shell.TurnSummary;

/**
 * Contexto da sessão: informativo, rolável, recolhível. Navega para detalhes;
 * não muda nada ([Layout §5](../../../../../../../docs/specs/ui/desktop-layout.md#5-inspector-e-cabeçalho)).
 *
 * <p>Só aparecem cartões com dado real. Segurança, Sistema e MCP chegam com os
 * marcos que os medem — a prévia os mostra com números ilustrativos, o produto não.
 */
final class Inspector extends ScrollPane {

    private final VBox cards = new VBox(12);
    private final DesktopState state;

    Inspector(DesktopState state) {
        this.state = state;
        getStyleClass().add("inspector");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setMinWidth(304);
        setPrefWidth(304);
        setMaxWidth(304);

        Label title = new Label("CONTEXTO DA SESSÃO");
        title.getStyleClass().add("inspector-title");
        VBox content = new VBox(12, title, cards);
        content.setPadding(new Insets(16));
        setContent(content);

        state.lastTurnProperty().addListener((observable, before, now) -> render());
        state.usageProperty().addListener((observable, before, now) -> render());
        state.diagnosticsProperty().addListener((observable, before, now) -> render());
        state.turnRunningProperty().addListener((observable, before, now) -> render());
        render();
    }

    private void render() {
        cards.getChildren().clear();
        state.providerWarning().ifPresent(warning -> cards.getChildren().add(card("Atenção", warningText(warning))));
        cards.getChildren().add(card("Execução atual", currentTurn()));
        cards.getChildren().add(card("Consumo desta sessão", usage(state.usageProperty().get())));
        cards.getChildren().add(card("Núcleo", core(state.diagnosticsProperty().get())));
    }

    private Node currentTurn() {
        if (state.turnRunningProperty().get()) {
            return muted("Turno em andamento…");
        }
        TurnSummary turn = state.lastTurnProperty().get();
        if (turn == null) {
            return muted("Nenhum turno ainda nesta sessão.");
        }
        return rows(
                "Respondido por", turn.localRoute() ? "rota local" : turn.provider(),
                "Modelo", turn.model(),
                "Tokens", (turn.estimated() ? "≈" : "") + turn.inputTokens() + " / " + turn.outputTokens(),
                "Cache lido", String.valueOf(turn.cacheReadTokens()),
                "Custo", TurnSummary.cost(turn.costUsd()),
                "Duração", Duration.ofMillis(turn.latencyMs()).toMillis() + " ms");
    }

    private Node usage(SessionUsage usage) {
        if (usage.turns() == 0) {
            return muted("Sem consumo ainda.");
        }
        return rows(
                "Turnos", String.valueOf(usage.turns()),
                "Tokens", usage.tokensLabel(),
                "Custo", (usage.estimated() ? "≈" : "") + TurnSummary.cost(usage.costUsd()));
    }

    private Node core(Diagnostics diagnostics) {
        if (diagnostics == null) {
            return muted("Aguardando o diagnóstico do núcleo.");
        }
        var conversation = diagnostics.roles().get("conversation");
        return rows(
                "Versão", diagnostics.version(),
                "No ar há", Duration.ofSeconds(diagnostics.uptimeSeconds()).toMinutes() + " min",
                "Conversa", conversation == null ? "—" : conversation.provider() + " · " + conversation.model());
    }

    private static VBox card(String title, Node body) {
        Label label = new Label(title);
        label.getStyleClass().add("card-title");
        VBox card = new VBox(10, label, body);
        card.getStyleClass().add("card");
        VBox.setVgrow(card, Priority.NEVER);
        return card;
    }

    static GridPane rows(String... pairs) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        for (int i = 0; i < pairs.length; i += 2) {
            Label key = new Label(pairs[i]);
            key.getStyleClass().add("row-key");
            Label value = new Label(pairs[i + 1]);
            value.getStyleClass().add("row-value");
            value.setWrapText(true);
            grid.addRow(i / 2, key, value);
        }
        return grid;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("muted");
        return label;
    }

    private static Label warningText(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("warning-text");
        return label;
    }
}
