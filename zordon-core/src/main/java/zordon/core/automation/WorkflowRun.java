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
package zordon.core.automation;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.core.event.ZordonEventBus;
import zordon.memory.TaskStore;

/**
 * Uma execução de fluxo: os passos em ordem, cada um uma vez por disparo, com
 * {@code when}, tentativas, {@code onError} e o resumo no fim.
 */
final class WorkflowRun {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final TaskStore store;
    private final ZordonEventBus bus;
    private final WorkflowEngine.Notifier notifier;
    private final WorkflowSteps steps;

    WorkflowRun(TaskStore store, ZordonEventBus bus, WorkflowEngine.Notifier notifier, WorkflowSteps steps) {
        this.store = store;
        this.bus = bus;
        this.notifier = notifier;
        this.steps = steps;
    }

    long tokensToday() {
        return steps.tokensToday();
    }

    private void taskState(String taskId, String state, String reason) {
        if (store.task(taskId).map(task -> "cancelled".equals(task.state())).orElse(false)) {
            state = "cancelled";
        }
        store.taskState(taskId, state, reason);
        bus.publish(EventType.TASK_STATE, Map.of("taskId", taskId, "state", state,
                "reason", reason == null ? "" : reason));
    }

    /** O resultado de um passo, com as tentativas já esgotadas. */
    private record Attempt(Map<String, Object> result, String error, boolean interrupted) {}

    WorkflowEngine.Outcome execute(AutomationSpec spec, String taskId) {
        String summary = null;
        boolean ok = true;
        for (AutomationSpec.Step step : spec.steps()) {
            TaskStore.TaskView view = store.task(taskId).orElseThrow();
            TaskStore.StepView saved = view.steps().stream().filter(candidate -> candidate.id().equals(step.id()))
                    .findFirst().orElseThrow();
            if (settled(saved, step)) {
                // idempotência: (automação, disparo, passo)
                ok &= !"failed".equals(saved.state());
                String title = savedTitle(saved);
                if (title != null) {
                    summary = title;
                }
                continue;
            }
            if (Thread.currentThread().isInterrupted() || "cancelled".equals(view.state())) {
                taskState(taskId, "cancelled".equals(view.state()) ? "cancelled" : "blocked", "execução interrompida");
                return new WorkflowEngine.Outcome(false, "execução interrompida", taskId);
            }
            Map<String, Map<String, Object>> context = context(view);
            if (!Expressions.when(step.when(), context)) {
                store.stepState(taskId, step.id(), "skipped", "when falso: " + step.when());
                continue;
            }
            store.stepState(taskId, step.id(), "running", null);
            Attempt attempt = attempt(spec, step, taskId, context);
            if (attempt.interrupted()) {
                taskState(taskId, "blocked", "execução interrompida");
                return new WorkflowEngine.Outcome(false, "execução interrompida", taskId);
            }
            store.completeStep(taskId, step.id(), WorkflowJson.write(attempt.result()),
                    attempt.error() == null ? "done" : "failed", attempt.error());
            if (attempt.error() == null) {
                if (attempt.result().get("title") instanceof String title) {
                    summary = title;
                }
                continue;
            }
            ok = false;
            if (carryOn(spec, step, attempt.error())) {
                continue;
            }
            summary = "parou no passo " + step.id() + ": " + attempt.error();
            break;
        }
        return finish(spec, taskId, ok, summary);
    }

    /** Passo já resolvido num disparo anterior: não roda de novo. */
    private static boolean settled(TaskStore.StepView saved, AutomationSpec.Step step) {
        return Set.of("done", "skipped").contains(saved.state())
                || "failed".equals(saved.state()) && !"stop".equals(step.onError());
    }

    private String savedTitle(TaskStore.StepView saved) {
        return saved.resultJson() != null && WorkflowJson.read(saved.resultJson()).get("title") instanceof String title
                ? title : null;
    }

    /** Roda o passo, repetindo até {@code retryAttempts}. Interrupção sai marcada, não lançada. */
    private Attempt attempt(AutomationSpec spec, AutomationSpec.Step step, String taskId,
            Map<String, Map<String, Object>> context) {
        Map<String, Object> result = null;
        String error = null;
        for (int number = 1; number <= step.retryAttempts(); number++) {
            try {
                result = steps.run(spec, step, taskId, context);
                error = result.get("error") instanceof String failure ? failure : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Attempt(null, null, true);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                result = new LinkedHashMap<>(Map.of("error", error));
            }
            if (error == null || number == step.retryAttempts()) {
                break;
            }
            log.info("automação {}: passo {} falhou ({}); nova tentativa em {} s", spec.id(), step.id(), error,
                    step.retryBackoff().toSeconds());
            if (!sleep(step.retryBackoff())) {
                return new Attempt(null, null, true);
            }
        }
        return new Attempt(result, error, false);
    }

    private static boolean sleep(Duration backoff) {
        try {
            Thread.sleep(backoff);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Avisa e diz se a automação segue. {@code false} para no passo. */
    private boolean carryOn(AutomationSpec spec, AutomationSpec.Step step, String error) {
        if ("skip".equals(step.onError())) {
            return true;
        }
        try {
            notifier.notify(spec, "A automação \"" + spec.name() + "\" falhou",
                "O passo " + step.id() + " não completou: " + error, "warning");
        } catch (RuntimeException e) {
            log.warn("não foi possível avisar sobre a falha: {}", e.toString());
        }
        return "notify".equals(step.onError());
    }

    private WorkflowEngine.Outcome finish(AutomationSpec spec, String taskId, boolean ok, String summary) {
        taskState(taskId, ok ? "done" : "failed", summary);
        String text = summary == null ? spec.steps().size() + " passo(s)" : summary;
        bus.publish(EventType.AUTOMATION_FINISHED, Map.of("automationId", spec.id(), "ok", ok, "summary", text,
                "taskId", taskId));
        log.info("automação {} terminou: {} ({})", spec.id(), ok ? "ok" : "falha", text);
        return new WorkflowEngine.Outcome(ok, text, taskId);
    }

    /** O contexto de interpolação: o evento e o resultado de cada passo já concluído. */
    private Map<String, Map<String, Object>> context(TaskStore.TaskView view) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (TaskStore.StepView step : view.steps()) {
            if (step.resultJson() == null || !Set.of("done", "failed").contains(step.state())) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>(WorkflowJson.read(step.resultJson()));
            data.remove("_workflow");
            out.put(WorkflowEngine.TRIGGER_STEP.equals(step.id()) ? "event" : step.id(), data);
        }
        out.putIfAbsent("event", Map.of());
        return out;
    }
}
