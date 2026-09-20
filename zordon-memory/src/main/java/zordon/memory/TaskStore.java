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
package zordon.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Planos duráveis (SPEC-023, ADR-0035): no mesmo banco da memória. O resultado de
 * uma etapa é gravado antes de a próxima começar, e transições só acrescentam.
 */
public interface TaskStore {

    /** Uma etapa como o Planner a devolveu, já validada. {@code doneWhen} é JSON. */
    record PlanStep(String id, String title, String agent, List<String> dependsOn, String risk, String doneWhen) {}

    record StepView(String id, int order, String title, String agent, List<String> dependsOn, String risk,
            String doneWhen, String state, int attempts, String resultJson) {}

    record TaskView(String id, String goal, String origin, String state, String reason, Instant createdAt,
            Instant updatedAt, List<StepView> steps) {}

    /** Um veredito do Verifier: a base do Evaluation Engine (Avaliação §4). */
    record Verdict(String taskId, String stepId, String agent, String model, String kind, String verdict,
            String reason, long tokens, long durationMs) {}

    String createTask(String goal, String origin, List<PlanStep> steps);

    void taskState(String taskId, String to, String reason);

    void stepState(String taskId, String stepId, String to, String reason);

    void stepResult(String taskId, String stepId, String resultJson);

    /** Resultado e estado final na mesma transação. */
    void completeStep(String taskId, String stepId, String resultJson, String state, String reason);

    void verdict(Verdict verdict);

    List<TaskView> tasks(int limit);

    Optional<TaskView> task(String taskId);

    /** Tarefas que estavam rodando quando o núcleo caiu. */
    List<TaskView> interrupted();

    Map<String, Object> taskStats();
}
