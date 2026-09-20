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

import java.util.List;
import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.security.Gatekeeper;

/** O modelo pode propor; a aprovação só existe no ZWP do desktop. */
public final class AutomationTools {
    public static Tool propose(AutomationEngine engine) {
        return new Tool() {
            @Override public String name() { return "automation.propose"; }
            @Override public String description() {
                return "Propõe uma automação para aprovação na tela: id, name, trigger (interval/every ISO-8601, "
                        + "schedule/cron/zone, event/event/match ou condition/metric/above/rearmBelow/sustainedFor), "
                        + "step [{id, tool,args ou agent,task ou notify:{title,body,severity}}]. Só GREEN. "
                        + "Use para monitorar API, avisar quando build termina ou container cai.";
            }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of("spec", Map.of("type", "object")),
                        "required", List.of("spec"));
            }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(); }
            @Override public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                if (!(args.get("spec") instanceof Map<?, ?>)) {
                    throw new ToolException("spec é obrigatório");
                }
                return new ActionDescriptor(name(), args, baseRisk(), effects(), List.of(), 0, List.of(),
                        "Propor uma automação para aprovação na tela");
            }
            @Override @SuppressWarnings("unchecked")
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) {
                Map<String, Object> proposal = engine.propose((Map<String, Object>) args.get("spec"));
                return new ToolResult("Proposta pronta. Aprove na seção Automações dos ajustes.\n"
                        + proposal.get("summary"), proposal);
            }
        };
    }
    private AutomationTools() {}
}
