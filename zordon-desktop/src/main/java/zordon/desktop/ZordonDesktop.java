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
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloResult;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;
import zordon.desktop.shell.TurnSummary;
import zordon.desktop.ui.Fonts;
import zordon.desktop.ui.ShellActions;
import zordon.desktop.ui.ZordonShell;
import zordon.zwp.CoreConnection;

/**
 * A janela do Zordon: liga a conexão com o núcleo ao estado, e o estado à tela.
 *
 * <p>Fechá-la não encerra nada: o núcleo é um serviço e continua trabalhando. A
 * interface é uma projeção do fluxo de eventos, não a dona do estado.
 */
public final class ZordonDesktop extends Application implements ShellActions {

    private static final Logger log = LoggerFactory.getLogger(ZordonDesktop.class);
    private static final String VERSION = "0.1.0";

    private final DesktopState state = new DesktopState();
    private final UiEventPump pump = new UiEventPump(this::apply);

    private ZordonShell shell;
    private CoreConnection connection;
    private Optional<ZordonTray> tray = Optional.empty();
    private Stage stage;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        Fonts.load();
        this.shell = new ZordonShell(state, this);

        // Janela compacta da SPEC-010: o console de voz é o conteúdo.
        Scene scene = new Scene(shell, 960, 720);
        scene.getStylesheets().add(ZordonDesktop.class.getResource("/zordon/desktop/zordon.css").toExternalForm());
        primary.setTitle("Zordon");
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            primary.getIcons().add(new javafx.scene.image.Image(ZordonDesktop.class.getResource(
                    "/zordon/desktop/icons/zordon-" + size + ".png").toExternalForm()));
        }
        primary.setMinWidth(720);
        primary.setMinHeight(560);
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

        tray = ZordonTray.install(this::showWindow, this::shutdown, () -> Platform.runLater(() -> {
            if (state.lockdownProperty().get()) {
                resumeZordon();
            } else {
                pauseZordon();
            }
        }));
        state.lockdownProperty().addListener((observable, before, now) -> tray.ifPresent(icon -> icon.lockdown(now)));
        state.connectionProperty().addListener((observable, before, now) -> tray.ifPresent(icon -> icon.show(
                switch (now) {
                    case ONLINE -> TrayState.ONLINE;
                    case CONNECTING -> TrayState.DEGRADED;
                    case OFFLINE -> TrayState.OFFLINE;
                })));
        pump.start();
        connect();
    }

    @Override
    public void stop() {
        shutdown();
    }

    // ── ações pedidas pelo shell ────────────────────────────────────────────

    @Override
    public void send(String text, ComposerTarget target) {
        if (target.newConversation()) {
            connection.request("chat.newSession", Map.of()).thenAccept(result -> Platform.runLater(() -> {
                state.sessionIdProperty().set(String.valueOf(result.get("sessionId")));
                shell.clearChat();
                state.select(Destination.CHAT);
                sendToSession(text);
            })).exceptionally(this::reportFailure);
        } else {
            sendToSession(text);
        }
    }

    @Override
    public void newConversation() {
        connection.request("chat.newSession", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            state.sessionIdProperty().set(String.valueOf(result.get("sessionId")));
            shell.clearChat();
            state.select(Destination.CHAT);
            shell.focusComposer();
        })).exceptionally(this::reportFailure);
    }

    @Override
    public void cancelTurn(String turnId) {
        if (turnId != null) {
            // Cancelar preserva o que já chegou e espera a confirmação do núcleo.
            connection.request("chat.cancel", Map.of("turnId", turnId))
                    .thenAccept(result -> { })
                    .exceptionally(this::reportFailure);
        }
    }

    @Override
    public void refreshDiagnostics() {
        connection.request("system.diagnostics", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.diagnostics(result)))
                .exceptionally(failure -> {
                    Platform.runLater(() -> state.diagnosticsFailed(rootMessage(failure)));
                    return null;
                });
    }

    @Override
    public void setVoiceMode(String mode) {
        connection.request("voice.setMode", Map.of("mode", mode))
                .thenAccept(result -> Platform.runLater(() -> state.voice(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void loadVoiceDevices() {
        connection.request("voice.devices", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.voiceDevices(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void selectVoiceDevice(String deviceId) {
        connection.request("voice.selectDevice", Map.of("deviceId", deviceId))
                .thenAccept(result -> Platform.runLater(() -> state.voice(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void testMicrophone() {
        connection.request("voice.testMicrophone", Map.of("seconds", 5))
                .thenAccept(result -> Platform.runLater(() -> state.voice(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void startListening() {
        connection.request("voice.startListening", Map.of("reason", "ui"))
                .thenAccept(result -> Platform.runLater(() -> state.voice(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void stopListening() {
        connection.request("voice.stopListening", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.voice(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void acknowledge(String messageId) {
        connection.request("notify.acknowledge", Map.of("messageId", messageId))
                .exceptionally(failure -> {
                    log.warn("confirmação do aviso {} não chegou ao núcleo: {}", messageId, rootMessage(failure));
                    return null;
                });
    }

    @Override
    public void pauseZordon() {
        connection.request("security.lockdown", Map.of("reason", "pausado pelo usuário na janela"))
                .thenAccept(result -> Platform.runLater(() -> state.lockdown(true, String.valueOf(
                        result.getOrDefault("reason", "")))))
                .exceptionally(this::reportFailure);
    }

    @Override
    public void resumeZordon() {
        connection.request("security.resume", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.lockdown(false, "")))
                .exceptionally(this::reportFailure);
    }

    @Override
    public void loadQuarantine() {
        connection.request("security.quarantine.list", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.quarantine().clear();
                    if (result.get("items") instanceof List<?> items) {
                        items.stream().filter(Map.class::isInstance).forEach(item -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) item;
                            state.quarantine().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void loadTasks() {
        // Fora da thread da conexão: aqui se espera outra resposta dela (task.get).
        connection.request("task.list", Map.of("limit", 10)).thenAcceptAsync(result -> {
            List<Map<String, Object>> loaded = new java.util.ArrayList<>();
            if (result.get("tasks") instanceof List<?> tasks) {
                for (Object item : tasks) {
                    if (!(item instanceof Map<?, ?> summary)) {
                        continue;
                    }
                    Map<String, Object> task = new java.util.LinkedHashMap<>();
                    summary.forEach((key, value) -> task.put(String.valueOf(key), value));
                    try {
                        Map<String, Object> detail = connection.request("task.get",
                                Map.of("taskId", String.valueOf(task.get("taskId")))).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        task.put("stepList", detail.getOrDefault("steps", List.of()));
                    } catch (Exception e) {
                        task.put("stepList", List.of());
                    }
                    loaded.add(task);
                }
            }
            Platform.runLater(() -> state.tasks().setAll(loaded));
        }, runnable -> Thread.ofVirtual().name("zordon-tasks").start(runnable)).exceptionally(failure -> null);
    }

    @Override
    public void confirmStep(String taskId, String stepId, boolean pass) {
        connection.request("task.confirm", Map.of("taskId", taskId, "stepId", stepId, "pass", pass))
                .thenAccept(result -> loadTasks())
                .exceptionally(this::reportFailure);
    }

    @Override
    public void resumeTask(String taskId) {
        connection.request("task.resume", Map.of("taskId", taskId))
                .thenAccept(result -> loadTasks())
                .exceptionally(this::reportFailure);
    }

    @Override
    public void loadAutomations() {
        connection.request("automation.list", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            for (String key : List.of("automations", "proposals")) {
                var target = "automations".equals(key) ? state.automations() : state.automationProposals();
                if (result.get(key) instanceof List<?> rows) {
                    List<Map<String, Object>> loaded = new java.util.ArrayList<>();
                    for (Object row : rows) {
                        if (row instanceof Map<?, ?> map) {
                            Map<String, Object> typed = new java.util.LinkedHashMap<>();
                            map.forEach((k, value) -> typed.put(String.valueOf(k), value));
                            loaded.add(typed);
                        }
                    }
                    target.setAll(loaded);
                }
            }
        })).exceptionally(this::reportFailure);
    }

    @Override
    public void approveAutomation(String proposalId) {
        automationRequest("automation.approve", Map.of("proposalId", proposalId));
    }

    @Override
    public void rejectAutomation(String proposalId) {
        automationRequest("automation.reject", Map.of("proposalId", proposalId));
    }

    @Override
    public void enableAutomation(String id, boolean enabled) {
        automationRequest(enabled ? "automation.enable" : "automation.disable", Map.of("id", id));
    }

    @Override
    public void runAutomation(String id) {
        automationRequest("automation.run", Map.of("id", id));
    }

    private void automationRequest(String method, Map<String, Object> params) {
        connection.request(method, params).thenAccept(result -> loadAutomations()).exceptionally(this::reportFailure);
    }

    @Override
    public void loadFindings() {
        connection.request("security.findings", Map.of("limit", 20))
                .thenAccept(result -> Platform.runLater(() -> {
                    state.findings().clear();
                    if (result.get("findings") instanceof List<?> findings) {
                        findings.stream().filter(Map.class::isInstance).forEach(finding -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) finding;
                            if (typed.get("acknowledgedAt") == null) {
                                state.findings().add(typed);
                            }
                        });
                    }
                }))
                .exceptionally(failure -> null);
        connection.request("security.breakers", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.breakers().clear();
                    if (result.get("breakers") instanceof List<?> breakers) {
                        breakers.stream().filter(Map.class::isInstance).forEach(breaker -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) breaker;
                            state.breakers().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void releaseBreaker(String subject, String mode) {
        connection.request("security.breakerRelease", Map.of("subject", subject, "mode", mode))
                .thenAccept(result -> loadFindings())
                .exceptionally(this::reportFailure);
    }

    @Override
    public void acknowledgeFinding(String findingId) {
        connection.request("security.findingAcknowledge", Map.of("findingId", findingId))
                .thenAccept(result -> loadFindings())
                .exceptionally(this::reportFailure);
    }

    @Override
    public void loadAgents() {
        connection.request("agent.list", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.agents().clear();
                    for (String key : List.of("agents", "invalid")) {
                        if (result.get(key) instanceof List<?> items) {
                            items.stream().filter(Map.class::isInstance).forEach(item -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> typed = (Map<String, Object>) item;
                                state.agents().add(typed);
                            });
                        }
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void loadMemory() {
        connection.request("memory.facts", Map.of("limit", 200))
                .thenAccept(result -> Platform.runLater(() -> {
                    state.memoryFacts().clear();
                    if (result.get("facts") instanceof List<?> facts) {
                        facts.stream().filter(Map.class::isInstance).forEach(fact -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) fact;
                            state.memoryFacts().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void forgetFact(String factId) {
        connection.request("memory.forget", Map.of("factId", factId))
                .thenAccept(result -> loadMemory())
                .exceptionally(this::reportFailure);
    }

    @Override
    public void loadMcp() {
        connection.request("mcp.servers", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.mcpServers().clear();
                    if (result.get("servers") instanceof List<?> servers) {
                        servers.stream().filter(Map.class::isInstance).forEach(server -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) server;
                            state.mcpServers().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    /**
     * As cargas das telas de destino (SPEC-030). Cada uma é um pedido só, feito
     * quando a tela abre — nunca na inicialização.
     */
    @Override
    public void loadSkills() {
        fill("tools.list", "tools", state.skills());
    }

    @Override
    public void loadSecurityEvents() {
        fill("security.events", "events", state.securityEvents());
    }

    @Override
    public void loadKnowledge() {
        connection.request("rag.status", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.knowledgeProperty().set(Map.copyOf(result))))
                .exceptionally(this::reportFailure);
    }

    @Override
    public void reindexKnowledge() {
        connection.request("rag.reindex", Map.of())
                .thenAccept(result -> loadKnowledge())
                .exceptionally(this::reportFailure);
    }

    @Override
    public void loadUsage() {
        connection.request("usage.summary", Map.of("days", 7))
                .thenAccept(result -> Platform.runLater(() -> state.usageSummaryProperty().set(Map.copyOf(result))))
                .exceptionally(this::reportFailure);
    }

    @Override
    public void loadSystem() {
        // Duas respostas numa tela só: as medidas e o estado do monitor (SPEC-024).
        connection.request("system.metrics", Map.of()).thenAcceptAsync(metrics -> {
            Map<String, Object> merged = new java.util.LinkedHashMap<>(metrics);
            try {
                Map<String, Object> monitor = connection.request("monitor.status", Map.of())
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                merged.putAll(monitor);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                merged.put("docker", Map.of("state", "desconhecido", "reason", "o monitor não respondeu"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(() -> state.systemMetricsProperty().set(Map.copyOf(merged)));
        }).exceptionally(this::reportFailure);
    }

    /** Uma lista de mapas do núcleo para uma lista observável da tela. */
    private void fill(String method, String key, javafx.collections.ObservableList<Map<String, Object>> target) {
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

    @Override
    public void approveMcp(String server) {
        connection.request("mcp.approve", Map.of("server", server))
                .thenAccept(result -> loadMcp())
                .exceptionally(this::reportFailure);
    }

    @Override
    public void restoreQuarantine(String vaultId) {
        state.quarantineNoticeProperty().set("Restaurando…");
        connection.request("security.quarantine.restore", Map.of("vaultId", vaultId))
                .thenAccept(result -> Platform.runLater(() -> {
                    state.quarantineNoticeProperty().set(String.valueOf(result.getOrDefault("text", "")));
                    loadQuarantine();
                }))
                .exceptionally(failure -> {
                    Platform.runLater(() -> state.quarantineNoticeProperty().set(rootMessage(failure)));
                    return null;
                });
    }

    /**
     * {@code ui.requestPermission} (SPEC-015 CA-1, CA-2): a janela vem para a
     * frente e o diálogo espera a decisão. Qualquer falha aqui é negar.
     */
    private Map<String, Object> requestPermission(Map<String, Object> params) {
        zordon.desktop.shell.SecurityPresentation.Prompt prompt =
                zordon.desktop.shell.SecurityPresentation.prompt(params);
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                showWindowNow();
                zordon.desktop.ui.PermissionPane.show(stage, prompt, stylesheet())
                        .whenComplete((approval, failure) -> answer.complete(failure == null ? approval : "deny"));
            } catch (RuntimeException e) {
                log.warn("diálogo de permissão falhou: {}", e.toString());
                answer.complete("deny");
            }
        });
        try {
            return Map.of("approval", answer.get(prompt.seconds() + 5L, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            return Map.of("approval", "deny");
        }
    }

    private static String stylesheet() {
        return ZordonDesktop.class.getResource("/zordon/desktop/zordon.css").toExternalForm();
    }

    private void refreshVoice() {
        connection.request("voice.status", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.voice(result)))
                .exceptionally(this::voiceFailure);
    }

    private Void voiceFailure(Throwable failure) {
        Platform.runLater(() -> state.voiceFailed(rootMessage(failure)));
        return null;
    }

    // ── conexão ──────────────────────────────────────────────────────────────

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
                        Platform.runLater(() -> state.offline(reason));
                    }

                    @Override
                    public void onEvent(EventEnvelope event) {
                        pump.offer(event);
                    }
                });
        connection.handle("ui.requestPermission", this::requestPermission);
        connection.start();
    }

    private void onConnected(HelloResult hello, boolean resumed) {
        state.online(hello.core().version());
        connection.request("session.subscribe",
                Map.of("topics", List.of(Topic.CHAT, Topic.SYSTEM, Topic.VOICE, Topic.SECURITY, Topic.MEMORY, Topic.AGENTS, Topic.AUTOMATION)));
        // O que ficou para confirmar enquanto a janela estava fora (SPEC-015 CA-4).
        connection.request("notify.pending", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            if (result.get("messages") instanceof List<?> messages) {
                messages.stream().filter(Map.class::isInstance).map(message -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) message;
                    return typed;
                }).forEach(this::notified);
            }
        })).exceptionally(failure -> null);
        loadQuarantine();
        loadMcp();
        loadMemory();
        loadAgents();
        loadTasks();
        loadFindings();
        loadAutomations();
        connection.request("security.status", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            if (result.get("lockdown") instanceof Map<?, ?> lockdown) {
                state.lockdown(Boolean.TRUE.equals(lockdown.get("active")),
                        String.valueOf(lockdown.get("reason") == null ? "" : lockdown.get("reason")));
            }
        })).exceptionally(failure -> null);
        refreshDiagnostics();
        // Sempre, mesmo retomando: o microfone é o estado que não pode estar velho.
        refreshVoice();
        if (!resumed) {
            // Sem continuidade: descarta o que tinha e recarrega, em vez de mostrar
            // um estado que o núcleo não reconhece mais (ADR-0011).
            shell.clearChat();
            loadHistory();
        }
    }

    private void loadHistory() {
        connection.request("chat.history", Map.of("limit", 50)).thenAccept(result -> {
            if (result.get("messages") instanceof List<?> messages) {
                Object session = result.get("sessionId");
                Platform.runLater(() -> {
                    state.sessionIdProperty().set(messages.isEmpty() ? null : String.valueOf(session));
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
                    shell.chatUser(text);
                    first = first.isEmpty() ? text : first;
                } else {
                    shell.chatAssistant(text, "");
                }
            }
        }
        shell.setLastConversation(first);
    }

    private void sendToSession(String text) {
        shell.chatUser(text);
        shell.setLastConversation(text);
        Map<String, Object> params = state.sessionIdProperty().get() == null
                ? Map.of("text", text)
                : Map.of("text", text, "sessionId", state.sessionIdProperty().get());
        connection.request("chat.send", params)
                .thenAccept(result -> Platform.runLater(
                        () -> state.sessionIdProperty().set(String.valueOf(result.get("sessionId")))))
                .exceptionally(this::reportFailure);
    }

    /** Aplica um lote de eventos. Roda na thread da interface, uma vez a cada 33 ms. */
    private void apply(List<EventEnvelope> batch) {
        for (EventEnvelope event : batch) {
            state.accept(event);
            // 20 níveis por segundo afogariam o log; o medidor já os mostra.
            if (event.type() != EventType.VOICE_LEVEL) {
                shell.logs().append(event);
            }
            Map<String, Object> payload = event.payload();
            String turnId = String.valueOf(payload.getOrDefault("turnId", ""));
            switch (event.type()) {
                case AI_THINKING -> {
                    if (payload.get("notice") instanceof String notice) {
                        shell.chatNotice(notice.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                                + notice.substring(1) + ".");
                    }
                    // A troca de quem responde é dita enquanto acontece, não depois.
                    if (payload.get("fallbackFrom") instanceof String from) {
                        shell.chatNotice("%s falhou (%s). Quem vai responder é a reserva: %s.".formatted(
                                from, payload.getOrDefault("reason", "sem motivo"), payload.getOrDefault("provider", "?")));
                    }
                }
                case AI_RESPONSE -> {
                    if (Boolean.TRUE.equals(payload.get("done"))) {
                        shell.chatComplete(turnId, String.valueOf(payload.getOrDefault("text", "")),
                                TurnSummary.fromResponse(payload).map(TurnSummary::footer).orElse(""));
                    } else if (payload.get("delta") instanceof String delta) {
                        shell.chatDelta(turnId, delta);
                    }
                }
                case SECURITY_NOTIFICATION -> {
                    notified(payload);
                    if (String.valueOf(payload.get("detectedBy")).startsWith("automation:")) {
                        loadAutomations();
                    }
                }
                case AUTOMATION_TRIGGERED, AUTOMATION_FINISHED -> loadAutomations();
                case MEMORY_WRITTEN -> loadMemory();
                case SECURITY_FINDING -> loadFindings();
                case TASK_STATE -> loadTasks();
                case SYSTEM_ALERT -> {
                    if ("mcp".equals(payload.get("source"))) {
                        loadMcp();
                    }
                }
                case AI_ERROR -> shell.chatError(payload.get("message")
                        + (Boolean.TRUE.equals(payload.get("retryable")) ? "  ·  dá para tentar de novo" : ""));
                default -> { }
            }
        }
    }

    private Void reportFailure(Throwable failure) {
        Platform.runLater(() -> shell.chatError(rootMessage(failure)));
        return null;
    }

    /** Um aviso chegou: CRITICAL abre a janela e pede confirmação de leitura (SPEC-015 CA-7). */
    private void notified(Map<String, Object> message) {
        state.notification(message);
        if (zordon.desktop.shell.SecurityPresentation.critical(String.valueOf(message.get("severity")))) {
            showWindowNow();
            zordon.desktop.ui.CriticalNotice.show(stage, message, stylesheet(), id -> {
                state.acknowledged(id);
                acknowledge(id);
            });
        }
    }

    private void showWindowNow() {
        stage.setIconified(false);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private void showWindow() {
        Platform.runLater(() -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    private void shutdown() {
        shell.close();
        pump.stop();
        if (connection != null) {
            connection.close();
        }
        tray.ifPresent(ZordonTray::remove);
        Platform.exit();
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return String.valueOf(cause.getMessage());
    }
}
