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
package zordon.desktop;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import zordon.api.event.EventEnvelope;

/**
 * Log de atividades: tudo o que o núcleo publicou, na ordem em que aconteceu.
 *
 * <p>É a tela que responde "o que ele fez, e por quê" sem depender da memória de
 * ninguém — e no M1 ela já mostra o turno inteiro, do comando ao custo.
 */
final class LogsView extends BorderPane {

    /** Teto de linhas. O log completo é do núcleo; a tela mostra a janela recente. */
    private static final int MAX_ROWS = 2_000;

    private final ObservableList<LogEntry> entries = FXCollections.observableArrayList();
    private final TableView<LogEntry> table = new TableView<>(entries);

    LogsView() {
        getStyleClass().add("logs-view");

        java.util.List.of(
                        column("Hora", "time", 90),
                        column("Seq", "seq", 70),
                        column("Tópico", "topic", 110),
                        column("Evento", "type", 170),
                        column("Detalhe", "detail", 520))
                .forEach(table.getColumns()::add);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Nada aconteceu ainda nesta sessão."));
        table.getStyleClass().add("logs-table");

        setCenter(table);
    }

    void append(EventEnvelope event) {
        entries.add(LogEntry.of(event));
        while (entries.size() > MAX_ROWS) {
            entries.removeFirst();
        }
        table.scrollTo(entries.size() - 1);
    }

    private TableColumn<LogEntry, ?> column(String title, String property, double width) {
        TableColumn<LogEntry, Object> column = new TableColumn<>(title);
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        column.setPrefWidth(width);
        return column;
    }
}
