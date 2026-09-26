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
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.Topic;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloResult;
import zordon.desktop.shell.DesktopState;
import zordon.zwp.CoreConnection;

/** A sessão com o núcleo: conecta e, a cada conexão, recarrega o que a janela mostra. */
final class DesktopSession {

    private static final Logger log = LoggerFactory.getLogger(ZordonDesktop.class);

    private final DesktopContext context;
    private final DesktopState state;
    private final DesktopActions actions;
    private final UiEventPump pump;

    DesktopSession(DesktopContext context, DesktopActions actions, UiEventPump pump) {
        this.context = context;
        this.state = context.state();
        this.actions = actions;
        this.pump = pump;
    }

    void connect() {
        CoreConnection connection = new CoreConnection(
                DesktopPaths.endpointFile(),
                new ClientInfo(ClientKind.DESKTOP, "zordon-desktop", ZordonDesktop.VERSION),
                List.of("ui.permission-prompt", "ui.notifications"),
                new CoreConnection.Listener() {
                    @Override
                    public void onOnline(HelloResult hello, boolean resumed) {
                        Platform.runLater(() -> onConnected(hello, resumed));
                    }

                    @Override
                    public void onOffline(String reason) {
                        Platform.runLater(() -> state.offline(reason));
                    }

                    @Override
                    public void onEvent(EventEnvelope event) {
                        pump.offer(event);
                    }
                });
        context.connection(connection);
        connection.handle("ui.requestPermission", actions.security()::requestPermission);
        connection.start();
    }

    private void onConnected(HelloResult hello, boolean resumed) {
        state.online(hello.core().version());
        context.request("session.subscribe",
                Map.of("topics", List.of(Topic.CHAT, Topic.SYSTEM, Topic.VOICE, Topic.SECURITY, Topic.MEMORY, Topic.AGENTS, Topic.AUTOMATION)));
        // O que ficou para confirmar enquanto a janela estava fora (SPEC-015 CA-4).
        context.request("notify.pending", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            if (result.get("messages") instanceof List<?> messages) {
                messages.stream().filter(Map.class::isInstance).map(message -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) message;
                    return typed;
                }).forEach(actions.security()::notified);
            }
        })).exceptionally(failure -> null);
        actions.security().loadQuarantine();
        actions.data().loadMcp();
        actions.memory().loadMemory();
        actions.data().loadAgents();
        actions.data().loadTasks();
        actions.security().loadFindings();
        actions.data().loadAutomations();
        actions.security().loadStatus();
        actions.refreshDiagnostics();
        // Sempre, mesmo retomando: o microfone é o estado que não pode estar velho.
        actions.voice().refresh();
        if (!resumed) {
            // Sem continuidade: descarta o que tinha e recarrega, em vez de mostrar
            // um estado que o núcleo não reconhece mais (ADR-0011).
            context.shell().clearChat();
            loadHistory();
        }
    }

    private void loadHistory() {
        context.request("chat.history", Map.of("limit", 50)).thenAccept(result -> {
            if (result.get("messages") instanceof List<?> messages) {
                Object session = result.get("sessionId");
                Platform.runLater(() -> {
                    state.conversation().sessionIdProperty().set(messages.isEmpty() ? null : String.valueOf(session));
                    renderHistory(messages);
                });
            }
        }).exceptionally(failure -> {
            log.debug("histórico indisponível: {}", failure.getMessage());
            return null;
        });
    }

    private void renderHistory(List<?> messages) {
        String first = "";
        // O núcleo devolve do mais recente para o mais antigo; a tela lê ao contrário.
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof Map<?, ?> message) {
                String text = String.valueOf(message.get("text"));
                if ("user".equals(message.get("role"))) {
                    context.shell().chatUser(text);
                    first = first.isEmpty() ? text : first;
                } else {
                    context.shell().chatAssistant(text, "");
                }
            }
        }
        context.shell().setLastConversation(first);
    }
}
