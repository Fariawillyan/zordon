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
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;
import zordon.zwp.CoreConnection;

/**
 * O botão de status do núcleo, no pé da coluna: o traço que muda de cor com a
 * conexão e, ao clicar, o detalhe dela e o atalho para o diagnóstico.
 */
final class CoreStatusButton extends Button {

    private final Rectangle core = new Rectangle(18, 2);
    private final Tooltip coreHint = new Tooltip();

    CoreStatusButton(DesktopState state, Consumer<Destination> onSelect) {
        super("");
        core.getStyleClass().add("rail-core");
        setGraphic(core);
        setId("core-status-button");
        getStyleClass().add("core-status-button");
        setMinSize(36, 32);
        setPrefSize(36, 32);
        setTooltip(coreHint);
        setAccessibleHelp("Abre o status da conexão e o acesso ao diagnóstico.");
        ContextMenu statusMenu = statusMenu(state, onSelect);
        setContextMenu(statusMenu);
        setOnAction(event -> {
            if (statusMenu.isShowing()) statusMenu.hide();
            else statusMenu.show(this, Side.RIGHT, 8, 0);
        });

        state.connection().stateProperty().addListener((observable, before, now) -> showCore(state));
        state.connection().detailProperty().addListener((observable, before, now) -> showCore(state));
        state.connection().reconnectionsProperty().addListener((observable, before, now) -> showCore(state));
        showCore(state);
    }

    /** Texto do indicador do núcleo, também dito pelo leitor de tela (SPEC-010 CA-4). */
    String coreText() {
        return coreHint.getText();
    }

    /** O traço que muda de cor com o estado do núcleo. */
    Rectangle indicator() {
        return core;
    }

    private static ContextMenu statusMenu(DesktopState state, Consumer<Destination> onSelect) {
        Label title = new Label("Status do Zordon");
        title.getStyleClass().add("core-status-title");
        Label connection = new Label();
        connection.setId("core-status-connection");
        connection.textProperty().bind(state.connection().label());
        Label detail = new Label();
        detail.textProperty().bind(state.connection().detailProperty());
        detail.setWrapText(true);
        detail.setMaxWidth(250);
        detail.visibleProperty().bind(detail.textProperty().isNotEmpty());
        detail.managedProperty().bind(detail.visibleProperty());
        Label drops = new Label();
        drops.textProperty().bind(state.connection().reconnectionsProperty().asString("Quedas nesta sessão: %d"));
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

    private void showCore(DesktopState state) {
        CoreConnection.State connection = state.connection().stateProperty().get();
        core.getStyleClass().removeAll("core-online", "core-connecting", "core-offline");
        core.getStyleClass().add("core-" + connection.name().toLowerCase(java.util.Locale.ROOT));
        String text = switch (connection) {
            case ONLINE -> "Núcleo conectado · " + state.connection().detailProperty().get();
            case CONNECTING -> "Conectando ao núcleo…";
            case OFFLINE -> "Núcleo offline — " + state.connection().detailProperty().get()
                    + " · quedas nesta sessão: " + state.connection().reconnectionsProperty().get();
        };
        coreHint.setText(text);
        setAccessibleText(text);
    }
}
