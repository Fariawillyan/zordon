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

import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import zordon.api.security.RequestOrigin;
import zordon.core.agents.AgentGuard;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRunner;
import zordon.core.agents.BudgetMeter;
import zordon.core.agents.TurnScope;
import zordon.memory.TaskStore;

/** Uma etapa: o agente dela roda com o orçamento da tarefa, e o resultado vai para o veredito. */
final class StepRun {

    private final TaskStore store;
    private final TaskRunner.Agents agents;
    private final StepVerdict verdict;
    private final TaskEvents events;
    private final LongSupplier nanos;
    private final Set<String> cancelled;

    StepRun(TaskStore store, TaskRunner.Agents agents, StepVerdict verdict, TaskEvents events, LongSupplier nanos,
            Set<String> cancelled) {
        this.store = store;
        this.agents = agents;
        this.verdict = verdict;
        this.events = events;
        this.nanos = nanos;
        this.cancelled = cancelled;
    }

    /** @return {@code false} quando a tarefa inteira precisa parar (orçamento) */
    boolean run(TaskStore.TaskView task, TaskStore.StepView step, Map<String, TaskStore.StepView> byId,
            BudgetMeter meter, RequestOrigin origin) {
        String taskId = task.id();
        store.stepState(taskId, step.id(), "running", null);
        events.publish(taskId, step.id(), "running", step.title(), task.goal(), null);
        AgentProfile agent = agents.registry().find(step.agent()).orElseGet(agents.registry()::general);
        TurnScope scope = new TurnScope(agent, agent.ceiling(), 0, origin, meter, new AgentGuard(nanos));
        String input = StepRecords.input(task, step, byId);
        long started = System.nanoTime();
        AgentRunner.Result result = agents.runner().run(scope, input, taskId + "/" + step.id(), null,
                () -> cancelled.contains(taskId));
        StepRecords.save(store, taskId, step.id(), result);
        if (!result.ok()) {
            boolean limit = result.reason().startsWith("limit:") || "cancelled".equals(result.reason());
            String to = limit ? "blocked" : "failed";
            store.stepState(taskId, step.id(), to, result.reason());
            events.publish(taskId, step.id(), to, step.title(), task.goal(), result.reason());
            if (limit && !"cancelled".equals(result.reason())) {
                store.taskState(taskId, "blocked", "orçamento: " + result.reason());
                events.publish(taskId, null, "blocked", null, task.goal(), "orçamento");
                return false;
            }
            return true;
        }
        return verdict.judge(task, step, agent.id(), result, started);
    }
}
