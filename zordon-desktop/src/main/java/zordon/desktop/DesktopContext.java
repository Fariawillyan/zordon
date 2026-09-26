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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.stage.Stage;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.ui.ZordonShell;
import zordon.zwp.CoreConnection;

/**
 * O que as partes da janela dividem: o estado, a conexão com o núcleo, o shell e
 * a janela. O shell e a janela chegam no {@code start} do JavaFX, e a conexão no
 * {@code connect}; por isso não são finais.
 */
final class DesktopContext {

    private final DesktopState state;
    private CoreConnection connection;
    private ZordonShell shell;
    private Stage stage;

    DesktopContext(DesktopState state) {
        this.state = state;
    }

    void attach(Stage window, ZordonShell screen) {
        this.stage = window;
        this.shell = screen;
    }

    void connection(CoreConnection value) {
        this.connection = value;
    }

    DesktopState state() {
        return state;
    }

    ZordonShell shell() {
        return shell;
    }

    Stage stage() {
        return stage;
    }

    CoreConnection connection() {
        return connection;
    }

    CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
        return connection.request(method, params);
    }

    /** Uma lista de mapas do núcleo para uma lista observável da tela. */
    void fill(String method, String key, ObservableList<Map<String, Object>> target) {
        connection.request(method, Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    target.clear();
                    if (result.get(key) instanceof List<?> rows) {
                        rows.stream().filter(Map.class::isInstance).forEach(row -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) row;
                            target.add(typed);
                        });
                    }
                }))
                .exceptionally(this::reportFailure);
    }

    /** A falha de um pedido vai para a conversa, como erro. */
    Void reportFailure(Throwable failure) {
        Platform.runLater(() -> shell.chatError(rootMessage(failure)));
        return null;
    }

    void showWindowNow() {
        stage.setIconified(false);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    static String stylesheet() {
        return ZordonDesktop.class.getResource("/zordon/desktop/zordon.css").toExternalForm();
    }

    static String rootMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return String.valueOf(cause.getMessage());
    }
}
