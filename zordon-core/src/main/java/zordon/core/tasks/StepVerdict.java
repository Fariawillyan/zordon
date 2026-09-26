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
import zordon.core.agents.AgentRunner;
import zordon.memory.TaskStore;

/** O veredito de uma etapa concluída pelo agente: o Verifier confere, e a etapa anda ou para (SPEC-023). */
final class StepVerdict {

    private final TaskStore store;
    private final Verifier verifier;
    private final TaskEvents events;

    StepVerdict(TaskStore store, Verifier verifier, TaskEvents events) {
        this.store = store;
        this.verifier = verifier;
        this.events = events;
    }

    /** @return sempre {@code true}: uma etapa reprovada não para a tarefa inteira */
    boolean judge(TaskStore.TaskView task, TaskStore.StepView step, String agentId, AgentRunner.Result result,
            long started) {
        String taskId = task.id();
        store.stepState(taskId, step.id(), "verifying", null);
        TaskStore.StepView fresh = store.task(taskId).orElseThrow().steps().stream()
                .filter(candidate -> candidate.id().equals(step.id())).findFirst().orElseThrow();
        Verifier.Outcome outcome = verifier.verify(taskId, task.goal(), fresh, result);
        store.verdict(new TaskStore.Verdict(taskId, step.id(), agentId, outcome.model(), outcome.kind(),
                outcome.verdict(), outcome.reason(), result.tokens() + outcome.tokens(),
                Duration.ofNanos(System.nanoTime() - started).toMillis()));
        String to = switch (outcome.verdict()) {
            case Verifier.PASS -> "done";
            case Verifier.FAIL -> "failed";
            default -> "waiting_human";
        };
        store.stepState(taskId, step.id(), to, outcome.reason());
        events.publish(taskId, step.id(), to, step.title(), task.goal(), outcome.reason());
        return true;
    }
}
