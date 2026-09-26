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

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import zordon.api.event.EventType;
import zordon.api.trace.Spec;
import zordon.core.agents.AgentRegistry;
import zordon.core.agents.AgentRunner;
import zordon.core.event.ZordonEventBus;
import zordon.core.tools.SkillRuntime;
import zordon.memory.AutomationStateStore;
import zordon.memory.TaskStore;

/**
 * Executa o workflow de uma automação (SPEC-025): passos em ordem, cada um gravado
 * antes do seguinte. Uma queda deixa a execução retomável, e passo concluído não
 * roda de novo.
 */
@Spec("SPEC-025")
public final class WorkflowEngine {

    static final String TRIGGER_STEP = "trigger";
    static final String AUTOMATIC = "{\"type\":\"automatic\"}";

    /** Quem entrega o aviso: fila durável e, com host, notificação do Windows. */
    public interface Notifier {
        void notify(AutomationSpec spec, String title, String body, String severity);
    }

    public record Outcome(boolean ok, String summary, String taskId) {}

    private final TaskStore store;
    private final ZordonEventBus bus;
    private final WorkflowRun run;

    /** O que executa um passo do fluxo: ferramentas, agentes, o runner e o aviso. */
    public record Engines(SkillRuntime tools, AgentRegistry agents, AgentRunner runner, Notifier notifier) {}

    public WorkflowEngine(TaskStore store, AutomationStateStore budget, Engines engines, ZordonEventBus bus,
            Clock clock, LongSupplier nanos) {
        this.store = store;
        this.bus = bus;
        this.run = new WorkflowRun(store, bus, engines.notifier(), new WorkflowSteps(engines.tools(), engines.notifier(),
                new AgentStep(engines.agents(), engines.runner(), budget, clock, nanos)));
    }

    /** Um disparo: cria a tarefa, grava o gatilho e executa. */
    public Outcome run(AutomationSpec spec, Map<String, Object> event) {
        return run.execute(spec, prepare(spec, event));
    }

    /** Grava a definição aprovada junto do disparo, antes de iniciar a thread. */
    public String prepare(AutomationSpec spec, Map<String, Object> event) {
        List<TaskStore.PlanStep> plan = new ArrayList<>();
        plan.add(new TaskStore.PlanStep(TRIGGER_STEP, "Disparo: " + spec.trigger().summary(), "automation",
                List.of(), "green", AUTOMATIC));
        String previous = TRIGGER_STEP;
        for (AutomationSpec.Step step : spec.steps()) {
            plan.add(new TaskStore.PlanStep(step.id(), step.title(), step.agent() == null ? "automation" : step.agent(),
                    List.of(previous), "green", AUTOMATIC));
            previous = step.id();
        }
        String taskId = store.createTask(spec.name(), "automation:" + spec.id(), plan);
        Map<String, Object> saved = new LinkedHashMap<>(event);
        saved.put("_workflow", spec.toToml());
        store.completeStep(taskId, TRIGGER_STEP, WorkflowJson.write(saved), "done", spec.trigger().summary());
        store.taskState(taskId, "running", null);
        bus.publish(EventType.AUTOMATION_TRIGGERED, Map.of("automationId", spec.id(), "name", spec.name(),
                "trigger", spec.trigger().summary(), "taskId", taskId));
        return taskId;
    }

    /** Continua uma execução que ficou pela metade (queda do núcleo, SPEC-023 CA-4). */
    public Outcome resume(AutomationSpec spec, String taskId) {
        TaskStore.TaskView view = store.task(taskId).orElseThrow();
        if (!view.origin().equals("automation:" + spec.id())
                || !Set.of("running", "planned", "blocked").contains(view.state())) {
            throw new IllegalArgumentException("tarefa não retomável por esta automação");
        }
        AutomationSpec original = definition(view);
        store.taskState(taskId, "running", "retomada");
        return run.execute(original, taskId);
    }

    public long tokensToday() {
        return run.tokensToday();
    }

    public AutomationSpec definition(TaskStore.TaskView view) {
        String toml = view.steps().stream().filter(step -> TRIGGER_STEP.equals(step.id()))
                .map(step -> WorkflowJson.read(step.resultJson()).get("_workflow")).filter(String.class::isInstance)
                .map(String.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("definição original ausente"));
        AutomationSpec spec = AutomationSpec.parseToml(toml);
        if (!view.origin().equals("automation:" + spec.id())) {
            throw new IllegalArgumentException("a definição não pertence à tarefa");
        }
        return spec;
    }

}
