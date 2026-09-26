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
package zordon.core.tasks;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import zordon.api.security.RequestOrigin;
import zordon.core.agents.Budget;
import zordon.core.agents.BudgetMeter;
import zordon.memory.TaskStore;

/**
 * O andamento de uma tarefa: a próxima etapa pronta, o bloqueio em cascata, o
 * orçamento da tarefa inteira e o estado final quando nada mais pode rodar.
 */
final class TaskDrive {

    /** O orçamento da tarefa inteira, repartido pelas etapas (SPEC-023 §3). */
    static final Budget TASK_BUDGET = new Budget(40, 60, 400_000, Duration.ofMinutes(30));

    private final TaskStore store;
    private final TaskEvents events;
    private final StepRun steps;
    private final LongSupplier nanos;
    private final Set<String> cancelled;

    TaskDrive(TaskStore store, TaskEvents events, Verifier verifier, TaskRunner.Agents agents, LongSupplier nanos,
            Set<String> cancelled) {
        this.store = store;
        this.events = events;
        this.steps = new StepRun(store, agents, new StepVerdict(store, verifier, events), events, nanos, cancelled);
        this.nanos = nanos;
        this.cancelled = cancelled;
    }

    /** Ao subir: o que estava rodando fica bloqueado, com o motivo. Nada roda sozinho. */
    List<TaskStore.TaskView> recover() {
        List<TaskStore.TaskView> interrupted = store.interrupted();
        for (TaskStore.TaskView task : interrupted) {
            for (TaskStore.StepView step : task.steps()) {
                if (Set.of("running", "verifying").contains(step.state())) {
                    store.stepState(task.id(), step.id(), "blocked", TaskRunner.RESTARTED);
                }
            }
            store.taskState(task.id(), "blocked", TaskRunner.RESTARTED);
            events.publish(task.id(), null, "blocked", null, task.goal(), TaskRunner.RESTARTED);
        }
        return interrupted;
    }

    void drive(String taskId, RequestOrigin origin) {
        BudgetMeter meter = new BudgetMeter(TASK_BUDGET, nanos);
        TaskStore.TaskView task = store.task(taskId).orElseThrow();
        store.taskState(taskId, "running", null);
        events.publish(taskId, null, "running", null, task.goal(), null);
        while (true) {
            task = store.task(taskId).orElseThrow();
            if (cancelled.remove(taskId)) {
                store.taskState(taskId, "cancelled", "cancelada pelo usuário");
                events.publish(taskId, null, "cancelled", null, task.goal(), "cancelada pelo usuário");
                return;
            }
            Map<String, TaskStore.StepView> byId = new LinkedHashMap<>();
            task.steps().forEach(step -> byId.put(step.id(), step));
            TaskStore.StepView next = nextStep(task, byId);
            if (next == null) {
                TaskStore.TaskView now = store.task(taskId).orElseThrow();
                if (progressPossible(now)) {
                    continue;
                }
                settle(now);
                return;
            }
            String over = meter.exceeded();
            if (over != null) {
                store.taskState(taskId, "blocked", "orçamento da tarefa: " + over);
                events.publish(taskId, null, "blocked", null, task.goal(), "orçamento da tarefa: " + over);
                return;
            }
            if (!steps.run(task, next, byId, meter, origin)) {
                return;
            }
        }
    }

    /**
     * A próxima etapa pronta para rodar, ou {@code null}.
     *
     * <p>Bloqueia no caminho a etapa cuja dependência não concluiu e devolve
     * {@code null}: quem chamou relê o estado, porque o bloqueio pode cascatear.
     */
    private TaskStore.StepView nextStep(TaskStore.TaskView task, Map<String, TaskStore.StepView> byId) {
        for (TaskStore.StepView step : ordered(task.steps())) {
            if (!"planned".equals(step.state())) {
                continue;
            }
            Optional<TaskStore.StepView> broken = step.dependsOn().stream().map(byId::get)
                    .filter(dep -> Set.of("failed", "blocked").contains(dep.state())).findFirst();
            if (broken.isPresent()) {
                store.stepState(task.id(), step.id(), "blocked", "a etapa " + broken.get().id() + " não concluiu");
                events.publish(task.id(), step.id(), "blocked", step.title(), task.goal(), "dependência não concluída");
                return null;
            }
            if (step.dependsOn().stream().allMatch(dep -> "done".equals(byId.get(dep).state()))) {
                return step;
            }
        }
        return null;
    }

    /** Ainda há etapa que pode andar, ou que ainda precisa ser bloqueada em cascata. */
    private static boolean progressPossible(TaskStore.TaskView now) {
        boolean anyPlannedReady = now.steps().stream().anyMatch(step -> "planned".equals(step.state())
                && step.dependsOn().stream().allMatch(dep -> now.steps().stream()
                        .anyMatch(other -> other.id().equals(dep) && "done".equals(other.state()))));
        boolean anyBlockable = now.steps().stream().anyMatch(step -> "planned".equals(step.state())
                && step.dependsOn().stream().anyMatch(dep -> now.steps().stream().anyMatch(other ->
                        other.id().equals(dep) && Set.of("failed", "blocked").contains(other.state()))));
        return anyPlannedReady || anyBlockable;
    }

    /** O estado da tarefa quando nada mais pode rodar. */
    private void settle(TaskStore.TaskView task) {
        List<String> states = task.steps().stream().map(TaskStore.StepView::state).toList();
        String to;
        if (states.stream().allMatch("done"::equals)) {
            to = "done";
        } else if (states.contains("waiting_human")) {
            to = "waiting_human";
        } else if (states.contains("failed")) {
            to = "failed";
        } else {
            to = "blocked";
        }
        if (!to.equals(task.state())) {
            store.taskState(task.id(), to, null);
        }
        events.publish(task.id(), null, to, null, task.goal(), null);
    }

    private static List<TaskStore.StepView> ordered(List<TaskStore.StepView> steps) {
        List<TaskStore.PlanStep> plan = steps.stream().map(step -> new TaskStore.PlanStep(step.id(), step.title(),
                step.agent(), step.dependsOn(), step.risk(), step.doneWhen())).toList();
        List<TaskStore.PlanStep> order = Planner.topological(plan);
        if (order == null) {
            return steps;
        }
        Map<String, TaskStore.StepView> byId = new LinkedHashMap<>();
        steps.forEach(step -> byId.put(step.id(), step));
        return order.stream().map(step -> byId.get(step.id())).toList();
    }
}
