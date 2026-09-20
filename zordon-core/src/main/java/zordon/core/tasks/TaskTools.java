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
import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.memory.TaskStore;
import zordon.security.Gatekeeper;

/**
 * {@code task.create}: "planeje e faça: …". Só o usuário pede; o modelo não abre
 * tarefas (SPEC-023 §3). Abrir o plano não faz nada no sistema: cada etapa passa
 * pelo motor com o teto do seu agente.
 */
@Spec("SPEC-023")
public final class TaskTools {

    public static Tool create(TaskRunner runner) {
        return new Tool() {
            @Override public String name() { return "task.create"; }
            @Override public String description() { return "Planeja e executa uma tarefa de vários passos."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(); }
            @Override public boolean modelVisible() { return false; }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                String goal = Tool.text(args, "goal");
                if (goal.length() > 1_000) {
                    throw new ToolException("o pedido é longo demais para um plano; resuma em até 1.000 caracteres");
                }
                return new ActionDescriptor(name(), args, RiskLevel.GREEN, Set.of(), List.of(), 1, List.of(),
                        "Planejar: " + goal);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                TaskRunner.Created created;
                try {
                    created = runner.create(Tool.text(args, "goal"), RequestOrigin.UI);
                } catch (Planner.PlanException e) {
                    throw new ToolException("não consegui montar o plano: " + e.getMessage());
                }
                List<String> titles = created.steps().stream().map(TaskStore.PlanStep::title).toList();
                return new ToolResult("Plano com " + titles.size() + (titles.size() == 1 ? " etapa: " : " etapas: ")
                        + String.join("; ", titles) + ". Começando.", Map.of("taskId", created.taskId()));
            }
        };
    }

    private TaskTools() {}
}
