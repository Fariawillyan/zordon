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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.Topic;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloResult;
import zordon.zwp.CoreConnection;

/**
 * A janela do Zordon.
 *
 * <p>Fechá-la não encerra nada: o núcleo é um serviço e continua trabalhando. A
 * interface é uma projeção do fluxo de eventos, não a dona do estado.
 */
public final class ZordonDesktop extends Application {

    private static final Logger log = LoggerFactory.getLogger(ZordonDesktop.class);
    private static final String VERSION = "0.1.0";

    private final Label status = new Label("NÚCLEO OFFLINE");
    private final Label detail = new Label("procurando o núcleo");
    private final UiEventPump pump = new UiEventPump(this::apply);

    private ChatView chat;
    private LogsView logs;
    private CoreConnection connection;
    private Optional<ZordonTray> tray = Optional.empty();
    private Stage stage;
    private String sessionId;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        this.chat = new ChatView(this::sendToCore);
        this.logs = new LogsView();

        TabPane tabs = new TabPane(
                tab("Chat", chat),
                tab("Logs", logs));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane();
        root.setCenter(tabs);
        root.setBottom(statusBar());
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 900, 640);
        scene.getStylesheets().add(
                ZordonDesktop.class.getResource("/zordon/desktop/zordon.css").toExternalForm());

        primary.setTitle("Zordon");
        primary.setScene(scene);
        // A janela some para a bandeja; o processo continua para receber eventos.
        Platform.setImplicitExit(false);
        primary.setOnCloseRequest(event -> {
            if (tray.isPresent()) {
                event.consume();
                primary.hide();
            } else {
                shutdown();
            }
        });
        primary.show();

        tray = ZordonTray.install(this::showWindow, this::shutdown);
        chat.setComposerEnabled(false, "aguardando o núcleo");
        pump.start();
        connect();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private Tab tab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private Region statusBar() {
        status.getStyleClass().add("core-status");
        detail.getStyleClass().add("core-detail");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label version = new Label("Zordon " + VERSION);
        version.getStyleClass().add("core-detail");

        HBox bar = new HBox(12, status, detail, spacer, version);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 24, 10, 24));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void connect() {
        connection = new CoreConnection(
                DesktopPaths.endpointFile(),
                new ClientInfo(ClientKind.DESKTOP, "zordon-desktop", VERSION),
                List.of("ui.permission-prompt", "ui.notifications"),
                new CoreConnection.Listener() {
                    @Override
                    public void onOnline(HelloResult hello, boolean resumed) {
                        Platform.runLater(() -> onConnected(hello, resumed));
                    }

                    @Override
                    public void onOffline(String reason) {
                        Platform.runLater(() -> onDisconnected(reason));
                    }

                    @Override
                    public void onEvent(EventEnvelope event) {
                        pump.offer(event);
                    }
                });
        connection.start();
    }

    private void onConnected(HelloResult hello, boolean resumed) {
        show(new CoreStatus(CoreConnection.State.ONLINE, "núcleo " + hello.core().version()));
        chat.setComposerEnabled(true, "");
        // O log de atividades é uma projeção de tudo, não só da conversa.
        connection.request("session.subscribe", Map.of("topics", List.of(Topic.CHAT, Topic.SYSTEM)));

        if (!resumed) {
            // Sem continuidade: o cliente descarta o que tinha e recarrega, em vez
            // de mostrar um estado que o núcleo não reconhece mais (ADR-0011).
            sessionId = null;
            loadHistory();
        }
    }

    private void onDisconnected(String reason) {
        show(CoreStatus.offline(reason));
        // O rascunho é preservado e o histórico já carregado continua legível:
        // desconexão não pode apagar o que o usuário estava escrevendo (UI §9).
        chat.setComposerEnabled(false, "núcleo offline — reconectando");
        chat.setThinking(false);
    }

    private void loadHistory() {
        connection.request("chat.history", Map.of("limit", 50)).thenAccept(result -> {
            Object messages = result.get("messages");
            if (messages instanceof List<?> list) {
                Platform.runLater(() -> renderHistory(list));
            }
        }).exceptionally(failure -> {
            log.debug("histórico indisponível: {}", failure.getMessage());
            return null;
        });
    }

    private void renderHistory(List<?> messages) {
        // O núcleo devolve do mais recente para o mais antigo; a tela lê ao contrário.
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof Map<?, ?> message) {
                String role = String.valueOf(message.get("role"));
                String text = String.valueOf(message.get("text"));
                if ("user".equals(role)) {
                    chat.addUserMessage(text);
                } else {
                    chat.completeAssistant("histórico-" + index, text, "");
                }
            }
        }
    }

    private void sendToCore(String text) {
        chat.addUserMessage(text);
        chat.setThinking(true);
        Map<String, Object> params = sessionId == null
                ? Map.of("text", text)
                : Map.of("text", text, "sessionId", sessionId);

        connection.request("chat.send", params)
                .thenAccept(result -> sessionId = String.valueOf(result.get("sessionId")))
                .exceptionally(failure -> {
                    Platform.runLater(() -> chat.showError(rootMessage(failure), true));
                    return null;
                });
    }

    /** Aplica um lote de eventos. Roda na thread da interface, uma vez a cada 33 ms. */
    private void apply(List<EventEnvelope> batch) {
        for (EventEnvelope event : batch) {
            logs.append(event);
            Map<String, Object> payload = event.payload();
            String turnId = String.valueOf(payload.getOrDefault("turnId", ""));
            switch (event.type()) {
                case AI_THINKING -> {
                    chat.setThinking(true);
                    // A troca de quem responde é dita enquanto acontece, não depois.
                    if (payload.get("fallbackFrom") instanceof String from) {
                        chat.showNotice("%s falhou (%s). Quem vai responder é a reserva: %s."
                                .formatted(from, payload.getOrDefault("reason", "sem motivo"),
                                        payload.getOrDefault("provider", "?")));
                    }
                }
                case AI_RESPONSE -> applyResponse(turnId, payload);
                case AI_ERROR -> chat.showError(
                        String.valueOf(payload.get("message")),
                        Boolean.TRUE.equals(payload.get("retryable")));
                // USER_COMMAND volta do núcleo por completude do log; a tela já
                // mostrou a mensagem quando o usuário a enviou.
                default -> { }
            }
        }
    }

    private void applyResponse(String turnId, Map<String, Object> payload) {
        if (Boolean.TRUE.equals(payload.get("done"))) {
            chat.completeAssistant(turnId, String.valueOf(payload.getOrDefault("text", "")), footer(payload));
        } else if (payload.get("delta") instanceof String delta) {
            chat.appendAssistantDelta(turnId, delta);
        }
    }

    /**
     * Uso e custo ficam visíveis desde o primeiro turno. Leitura de cache sempre
     * zero é o sintoma do maior risco deste marco, e número que ninguém vê não é
     * número (docs/roadmap.md).
     */
    static String footer(Map<String, Object> payload) {
        if (!(payload.get("usage") instanceof Map<?, ?> usage)) {
            return "";
        }
        long input = number(usage.get("inputTokens"));
        long output = number(usage.get("outputTokens"));
        long cacheRead = number(usage.get("cacheReadTokens"));
        if (input + output == 0) {
            return "resposta local · sem custo";
        }
        // "≈" quando o provider não informou o consumo: estimativa exibida como
        // medida seria mentira com cara de número.
        String approx = Boolean.TRUE.equals(usage.get("estimated")) ? "≈" : "";
        String cost = payload.get("costUsd") instanceof String value
                ? "US$ " + new BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toPlainString()
                : "—";
        String who = payload.get("provider") instanceof String provider
                ? provider + " · " + payload.getOrDefault("model", "?") + " · "
                : "";
        String fallback = payload.get("fallbackFrom") instanceof String from ? " · reserva no lugar de " + from : "";
        return "%s%s%d tok entrada · %s%d tok saída · %d de cache · %s%s"
                .formatted(who, approx, input, approx, output, cacheRead, cost, fallback);
    }

    private static long number(Object value) {
        return value instanceof Number typed ? typed.longValue() : 0L;
    }

    private void show(CoreStatus current) {
        status.setText(current.label());
        status.getStyleClass().removeAll("state-online", "state-offline", "state-connecting");
        status.getStyleClass().add("state-" + current.state().name().toLowerCase(java.util.Locale.ROOT));
        detail.setText(current.detail());
        tray.ifPresent(icon -> icon.show(switch (current.state()) {
            case ONLINE -> TrayState.ONLINE;
            case CONNECTING -> TrayState.DEGRADED;
            case OFFLINE -> TrayState.OFFLINE;
        }));
    }

    private void showWindow() {
        Platform.runLater(() -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    private void shutdown() {
        pump.stop();
        if (connection != null) {
            connection.close();
        }
        tray.ifPresent(ZordonTray::remove);
        Platform.exit();
    }

    private String rootMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return String.valueOf(cause.getMessage());
    }
}
