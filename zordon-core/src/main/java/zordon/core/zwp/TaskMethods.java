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
package zordon.core.zwp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import zordon.api.security.RequestOrigin;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.tasks.Planner;
import zordon.core.tasks.TaskRunner;
import zordon.memory.TaskStore;

/** {@code task.*} (SPEC-023). Retomar e confirmar são decisões na tela. */
@Spec("SPEC-023")
public final class TaskMethods {

    private final TaskStore store;
    private final TaskRunner runner;
    private zordon.core.automation.AutomationEngine automations;

    public TaskMethods automations(zordon.core.automation.AutomationEngine engine) {
        this.automations = engine;
        return this;
    }

    private boolean automated(String taskId) {
        return store.task(taskId).map(task -> task.origin().startsWith("automation:")).orElse(false);
    }

    private boolean resume(String taskId) {
        return automated(taskId) ? automations != null && automations.resume(taskId) : runner.resume(taskId);
    }

    private boolean cancel(String taskId) {
        return automated(taskId) ? automations != null && automations.cancel(taskId) : runner.cancel(taskId);
    }

    public TaskMethods(TaskStore store, TaskRunner runner) {
        this.store = Objects.requireNonNull(store, "store");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public void registerOn(ZwpServer server) {
        server.register("task.create", (session, params) -> {
            String goal = required(params, "goal");
            try {
                TaskRunner.Created created = runner.create(goal, RequestOrigin.UI);
                return Map.of("taskId", created.taskId(), "steps", created.steps().stream().map(step -> Map.of(
                        "id", step.id(), "title", step.title(), "agent", step.agent())).toList());
            } catch (Planner.PlanException e) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_AI_UNAVAILABLE, e.getMessage());
            }
        }).register("task.list", (session, params) -> {
            int limit = params.get("limit") instanceof Number number ? number.intValue() : 20;
            return Map.of("tasks", store.tasks(limit).stream().map(TaskMethods::summary).toList());
        }).register("task.get", (session, params) -> {
            TaskStore.TaskView task = store.task(required(params, "taskId")).orElseThrow(() ->
                    new ZwpMethodException(ZwpErrorKind.ERR_NOT_FOUND, "tarefa desconhecida"));
            return Map.of("task", summary(task), "steps", task.steps().stream().map(TaskMethods::step).toList());
        }).register("task.cancel", (session, params) -> Map.of("cancelled", cancel(required(params, "taskId"))))
                .register("task.resume", (session, params) -> {
                    desktopOnly(session);
                    return Map.of("resumed", resume(required(params, "taskId")));
                }).register("task.confirm", (session, params) -> {
                    desktopOnly(session);
                    if (!(params.get("pass") instanceof Boolean pass)) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "pass é obrigatório");
                    }
                    return Map.of("state", runner.confirm(required(params, "taskId"), required(params, "stepId"), pass)
                            .orElseThrow(() -> new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT,
                                    "a etapa não está esperando confirmação")));
                });
    }

    private static void desktopOnly(ZwpSession session) {
        if (!session.is(ClientKind.DESKTOP)) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED, "só a janela do Zordon decide isto");
        }
    }

    private static String required(Map<String, Object> params, String key) {
        if (!(params.get(key) instanceof String value) || value.isBlank()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, key + " é obrigatório");
        }
        return value;
    }

    static Map<String, Object> summary(TaskStore.TaskView task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.id());
        out.put("goal", task.goal());
        out.put("state", task.state());
        if (task.reason() != null) {
            out.put("reason", task.reason());
        }
        out.put("steps", task.steps().size());
        out.put("done", task.steps().stream().filter(step -> "done".equals(step.state())).count());
        out.put("createdAt", task.createdAt().toString());
        return out;
    }

    static Map<String, Object> step(TaskStore.StepView step) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", step.id());
        out.put("title", step.title());
        out.put("agent", step.agent());
        out.put("state", step.state());
        out.put("dependsOn", step.dependsOn());
        out.put("doneWhen", step.doneWhen());
        if (step.resultJson() != null) {
            out.put("result", step.resultJson());
        }
        return out;
    }
}
