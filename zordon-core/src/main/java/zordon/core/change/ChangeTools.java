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
package zordon.core.change;

import java.util.List;
import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.security.Gatekeeper;

/** {@code change.plan}: o preflight de nove passos, sem executar nada (SPEC-029). */
@Spec("SPEC-029")
public final class ChangeTools {

    public static Tool plan(Preflight preflight) {
        return new Tool() {
            @Override public String name() { return "change.plan"; }

            @Override
            public String description() {
                return "Monta o preflight de nove passos para alterar um projeto. Só planeja: nada é alterado.";
            }

            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(); }
            @Override public boolean modelVisible() { return false; }
            @Override public Map<String, Object> inputSchema() { return Tool.schema("goal", "o que se quer mudar"); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                String goal = Tool.text(args, "goal");
                if (goal.length() > 500) {
                    throw new ToolException("o objetivo é longo demais; resuma em até 500 caracteres");
                }
                return new ActionDescriptor(name(), args, RiskLevel.GREEN, Set.of(), List.of(), 0, List.of(),
                        "Planejar a mudança: " + goal);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                Preflight.Plan plan = preflight.plan(Tool.text(args, "goal"));
                return new ToolResult(Preflight.summary(plan), Map.of("taskId", plan.taskId(),
                        "touchesTrustCore", plan.touchesTrustCore()));
            }
        };
    }

    private ChangeTools() {}
}
