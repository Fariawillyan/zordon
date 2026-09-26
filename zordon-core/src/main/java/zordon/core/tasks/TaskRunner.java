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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.RequestOrigin;
import zordon.api.trace.Spec;
import zordon.core.agents.AgentRegistry;
import zordon.core.agents.AgentRunner;
import zordon.core.event.ZordonEventBus;
import zordon.memory.TaskStore;

/**
 * O orquestrador de planos (SPEC-023): uma etapa de cada vez, em ordem topológica,
 * cada uma verificada antes de contar. O estado mora no {@link TaskStore}: o
 * resultado é gravado antes da etapa seguinte, e uma queda deixa tudo retomável.
 */
@Spec("SPEC-023")
public final class TaskRunner {

    static final String RESTARTED = "o núcleo reiniciou";

    public record Created(String taskId, List<TaskStore.PlanStep> steps) {}

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final TaskStore store;
    private final Planner planner;
    private final TaskEvents events;
    private final TaskDrive drive;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** O par que executa os agentes: o registro e o runner. */
    public record Agents(AgentRegistry registry, AgentRunner runner) {}

    public TaskRunner(TaskStore store, Planner planner, Verifier verifier, Agents agents,
            ZordonEventBus bus, LongSupplier nanos) {
        this.store = store;
        this.planner = planner;
        this.events = new TaskEvents(bus);
        this.drive = new TaskDrive(store, events, verifier, agents, nanos, cancelled);
    }

    /** Planeja, grava, comunica e começa. O plano é dito antes de qualquer etapa rodar. */
    public Created create(String goal, RequestOrigin origin) throws Planner.PlanException {
        List<TaskStore.PlanStep> steps = planner.plan(goal);
        String taskId = store.createTask(goal, origin.wire(), steps);
        events.publish(taskId, null, "planned", null, goal, "plano com " + steps.size() + " etapa(s)");
        start(taskId, origin);
        return new Created(taskId, steps);
    }

    public boolean cancel(String taskId) {
        Optional<TaskStore.TaskView> task = store.task(taskId);
        if (task.isEmpty() || Set.of("done", "failed", "cancelled").contains(task.get().state())) {
            return false;
        }
        cancelled.add(taskId);
        if (!active.contains(taskId)) {
            store.taskState(taskId, "cancelled", "cancelada pelo usuário");
            events.publish(taskId, null, "cancelled", null, task.get().goal(), "cancelada pelo usuário");
        }
        return true;
    }

    /** Só pela tela: etapas bloqueadas voltam a planejadas, e a tarefa segue (Planner §5). */
    public boolean resume(String taskId) {
        Optional<TaskStore.TaskView> task = store.task(taskId);
        if (task.isEmpty() || task.get().origin().startsWith("automation:")
                || !"blocked".equals(task.get().state()) || active.contains(taskId)) {
            return false;
        }
        for (TaskStore.StepView step : task.get().steps()) {
            if ("blocked".equals(step.state())) {
                store.stepState(taskId, step.id(), "planned", "retomada pelo usuário");
            }
        }
        start(taskId, RequestOrigin.UI);
        return true;
    }

    /** O veredito humano de uma etapa em {@code waiting_human}. */
    public Optional<String> confirm(String taskId, String stepId, boolean pass) {
        Optional<TaskStore.TaskView> task = store.task(taskId);
        Optional<TaskStore.StepView> step = task.flatMap(view -> view.steps().stream()
                .filter(candidate -> candidate.id().equals(stepId)).findFirst());
        if (step.isEmpty() || !"waiting_human".equals(step.get().state())) {
            return Optional.empty();
        }
        store.verdict(new TaskStore.Verdict(taskId, stepId, step.get().agent(), null, "human",
                pass ? Verifier.PASS : Verifier.FAIL, "confirmado na tela", 0, 0));
        String to = pass ? "done" : "failed";
        store.stepState(taskId, stepId, to, pass ? "confirmada na tela" : "reprovada na tela");
        events.publish(taskId, stepId, to, step.get().title(), task.get().goal(), null);
        if (!active.contains(taskId)) {
            start(taskId, RequestOrigin.UI);
        }
        return Optional.of(to);
    }

    /** Ao subir: o que estava rodando fica bloqueado, com o motivo. Nada roda sozinho. */
    public List<TaskStore.TaskView> recover() {
        return drive.recover();
    }

    private void start(String taskId, RequestOrigin origin) {
        if (!active.add(taskId)) {
            return;
        }
        Thread.ofVirtual().name("zordon-task-" + taskId).start(() -> {
            try {
                drive.drive(taskId, origin);
            } catch (RuntimeException e) {
                log.warn("tarefa {} parou com erro: {}", taskId, e.toString());
                store.taskState(taskId, "blocked", "erro interno: " + e.getMessage());
                events.publish(taskId, null, "blocked", null, null, "erro interno");
            } finally {
                active.remove(taskId);
            }
        });
    }

}
