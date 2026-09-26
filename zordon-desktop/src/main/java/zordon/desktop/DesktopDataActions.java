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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Platform;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.ui.ShellDataActions;

/** O trabalho pedido pela tela: servidores MCP, tarefas, automações, agentes, ferramentas, uso e sistema. */
final class DesktopDataActions implements ShellDataActions {

    private final DesktopContext context;
    private final DesktopState state;

    DesktopDataActions(DesktopContext context) {
        this.context = context;
        this.state = context.state();
    }

    @Override
    public void loadMcp() {
        context.request("mcp.servers", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.resources().mcpServers().clear();
                    if (result.get("servers") instanceof List<?> servers) {
                        servers.stream().filter(Map.class::isInstance).forEach(server -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) server;
                            state.resources().mcpServers().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void approveMcp(String server) {
        context.request("mcp.approve", Map.of("server", server))
                .thenAccept(result -> loadMcp())
                .exceptionally(context::reportFailure);
    }

    @Override
    public void loadTasks() {
        // Fora da thread da conexão: aqui se espera outra resposta dela (task.get).
        context.request("task.list", Map.of("limit", 10)).thenAcceptAsync(result -> {
            List<Map<String, Object>> loaded = new ArrayList<>();
            if (result.get("tasks") instanceof List<?> tasks) {
                for (Object item : tasks) {
                    if (!(item instanceof Map<?, ?> summary)) {
                        continue;
                    }
                    Map<String, Object> task = new LinkedHashMap<>();
                    summary.forEach((key, value) -> task.put(String.valueOf(key), value));
                    try {
                        Map<String, Object> detail = context.request("task.get",
                                Map.of("taskId", String.valueOf(task.get("taskId")))).get(5, TimeUnit.SECONDS);
                        task.put("stepList", detail.getOrDefault("steps", List.of()));
                    } catch (Exception e) {
                        task.put("stepList", List.of());
                    }
                    loaded.add(task);
                }
            }
            Platform.runLater(() -> state.resources().tasks().setAll(loaded));
        }, runnable -> Thread.ofVirtual().name("zordon-tasks").start(runnable)).exceptionally(failure -> null);
    }

    @Override
    public void confirmStep(String taskId, String stepId, boolean pass) {
        context.request("task.confirm", Map.of("taskId", taskId, "stepId", stepId, "pass", pass))
                .thenAccept(result -> loadTasks())
                .exceptionally(context::reportFailure);
    }

    @Override
    public void resumeTask(String taskId) {
        context.request("task.resume", Map.of("taskId", taskId))
                .thenAccept(result -> loadTasks())
                .exceptionally(context::reportFailure);
    }

    @Override
    public void loadAutomations() {
        context.request("automation.list", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            for (String key : List.of("automations", "proposals")) {
                var target = "automations".equals(key) ? state.resources().automations() : state.resources().automationProposals();
                if (result.get(key) instanceof List<?> rows) {
                    List<Map<String, Object>> loaded = new ArrayList<>();
                    for (Object row : rows) {
                        if (row instanceof Map<?, ?> map) {
                            Map<String, Object> typed = new LinkedHashMap<>();
                            map.forEach((k, value) -> typed.put(String.valueOf(k), value));
                            loaded.add(typed);
                        }
                    }
                    target.setAll(loaded);
                }
            }
        })).exceptionally(context::reportFailure);
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
        context.request(method, params).thenAccept(result -> loadAutomations()).exceptionally(context::reportFailure);
    }

    @Override
    public void loadAgents() {
        context.request("agent.list", Map.of())
                .thenAccept(result -> Platform.runLater(() -> {
                    state.resources().agents().clear();
                    for (String key : List.of("agents", "invalid")) {
                        if (result.get(key) instanceof List<?> items) {
                            items.stream().filter(Map.class::isInstance).forEach(item -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> typed = (Map<String, Object>) item;
                                state.resources().agents().add(typed);
                            });
                        }
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
        context.fill("tools.list", "tools", state.resources().skills());
    }

    @Override
    public void loadUsage() {
        context.request("usage.summary", Map.of("days", 7))
                .thenAccept(result -> Platform.runLater(() -> state.resources().usageSummaryProperty().set(Map.copyOf(result))))
                .exceptionally(context::reportFailure);
    }

    @Override
    public void loadSystem() {
        // Duas respostas numa tela só: as medidas e o estado do monitor (SPEC-024).
        context.request("system.metrics", Map.of()).thenAcceptAsync(metrics -> {
            Map<String, Object> merged = new LinkedHashMap<>(metrics);
            try {
                Map<String, Object> monitor = context.request("monitor.status", Map.of())
                        .get(5, TimeUnit.SECONDS);
                merged.putAll(monitor);
            } catch (TimeoutException | ExecutionException e) {
                merged.put("docker", Map.of("state", "desconhecido", "reason", "o monitor não respondeu"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(() -> state.resources().systemMetricsProperty().set(Map.copyOf(merged)));
        }).exceptionally(context::reportFailure);
    }
}
