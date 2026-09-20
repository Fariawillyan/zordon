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
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Diagnostics;

/**
 * Diagnóstico mínimo: o núcleo está bem, e quem vai responder? (SPEC-005 §12).
 *
 * <p>Estado dos providers vem do núcleo, com nomes e nunca chaves.
 */
final class DiagnosticsView extends ScrollPane {

    private final VBox content = new VBox(16);
    private final DesktopState state;
    private final Runnable refresh;

    DiagnosticsView(DesktopState state, Runnable refresh) {
        this.state = state;
        this.refresh = refresh;
        getStyleClass().add("page");
        setFitToWidth(true);
        content.setPadding(new Insets(20, 24, 24, 24));
        content.setMaxWidth(880);
        setContent(content);
        state.diagnosticsProperty().addListener((observable, before, now) -> render());
        state.diagnosticsErrorProperty().addListener((observable, before, now) -> render());
        state.reconnectionsProperty().addListener((observable, before, now) -> render());
        render();
    }

    private void render() {
        Label crumb = new Label("OPERAÇÃO");
        crumb.getStyleClass().add("crumb");
        Label title = new Label("Diagnóstico");
        title.getStyleClass().add("page-title");
        Button update = new Button("Atualizar");
        update.getStyleClass().add("button-secondary");
        update.setOnAction(event -> refresh.run());
        content.getChildren().setAll(new VBox(4, crumb, title), update);

        if (!state.diagnosticsErrorProperty().get().isEmpty()) {
            Label error = new Label("Diagnóstico indisponível: " + state.diagnosticsErrorProperty().get());
            error.getStyleClass().add("warning-text");
            content.getChildren().add(error);
        }
        Diagnostics diagnostics = state.diagnosticsProperty().get();
        content.getChildren().add(HomeView.section("Esta janela", Inspector.rows(
                "Conexão", state.coreLabel().get(),
                "Quedas nesta sessão", String.valueOf(state.reconnectionsProperty().get()))));
        if (diagnostics == null) {
            return;
        }
        content.getChildren().add(HomeView.section("Núcleo", Inspector.rows(
                "Versão", diagnostics.version(),
                "Execução (startId)", diagnostics.startId(),
                "No ar há", readable(Duration.ofSeconds(diagnostics.uptimeSeconds())),
                "Eventos publicados", String.valueOf(diagnostics.lastSeq()),
                "Clientes conectados", String.valueOf(diagnostics.clients()),
                "Turnos em andamento", String.valueOf(diagnostics.activeTurns()))));
        content.getChildren().add(HomeView.section("Providers", pairs(diagnostics.providers())));
        VBox roles = new VBox(8);
        diagnostics.roles().forEach((role, value) -> roles.getChildren().add(Inspector.rows(
                role, value.provider() + " · " + value.model() + (value.ready() ? " — pronto" : " — " + value.reason()))));
        content.getChildren().add(HomeView.section("Papéis", roles));
    }

    private static javafx.scene.Node pairs(Map<String, String> values) {
        String[] flat = values.entrySet().stream()
                .flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue()))
                .toArray(String[]::new);
        return flat.length == 0 ? new Label("Nenhum provider configurado.") : Inspector.rows(flat);
    }

    private static String readable(Duration duration) {
        long hours = duration.toHours();
        return hours > 0 ? hours + " h " + duration.toMinutesPart() + " min" : duration.toMinutes() + " min";
    }
}
