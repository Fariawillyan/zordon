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

import zordon.api.security.RiskLevel;
import zordon.core.agents.AgentRegistry;
import zordon.core.tools.SkillRuntime;
import zordon.core.tools.ToolException;

/** O que uma automação pode pedir: nada acima de GREEN, e só agentes que existem. */
final class AutomationValidation {

    private final SkillRuntime tools;
    private final AgentRegistry agents;

    AutomationValidation(SkillRuntime tools, AgentRegistry agents) {
        this.tools = tools;
        this.agents = agents;
    }

    /** Só agentes conhecidos e ferramentas GREEN, com argumentos que não elevam o risco. */
    void check(AutomationSpec spec) {
        for (AutomationSpec.Step step : spec.steps()) {
            if (step.agent() != null && agents.find(step.agent()).isEmpty()) {
                throw new IllegalArgumentException("agente desconhecido: " + step.agent());
            }
            if (step.tool() != null) {
                if (tools.baseRisk(step.tool()).orElse(RiskLevel.RED) != RiskLevel.GREEN) {
                    throw new IllegalArgumentException("automação só aceita ferramenta GREEN existente: " + step.tool());
                }
                // Argumentos interpolados serão descritos e autorizados novamente em cada chamada.
                if (!step.args().toString().contains("{{")) {
                    try {
                        if (tools.describe(step.tool(), step.args()).orElseThrow().baseRisk() != RiskLevel.GREEN) {
                            throw new IllegalArgumentException("argumentos elevam o risco de " + step.tool());
                        }
                    } catch (ToolException e) {
                        throw new IllegalArgumentException(e.getMessage(), e);
                    }
                }
            }
        }
    }
}
