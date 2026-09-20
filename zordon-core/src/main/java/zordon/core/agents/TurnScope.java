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
package zordon.core.agents;

import java.util.List;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;

/**
 * O agente de um turno ou de uma delegação, com o teto já calculado e o orçamento
 * e o disjuntor em curso (SPEC-022).
 *
 * @param ceiling o teto efetivo: o do agente, ou o menor entre o do filho e o do pai
 * @param depth 0 no turno; 1 num sub-agente, que não delega de novo
 */
public record TurnScope(AgentProfile agent, RiskLevel ceiling, int depth, RequestOrigin origin, BudgetMeter meter,
        AgentGuard guard, String actor) {

    public static final String DELEGATE = "agent.delegate";

    /** Sem ator próprio: quem aparece na auditoria é o usuário (ou o agente, se delegado). */
    public TurnScope(AgentProfile agent, RiskLevel ceiling, int depth, RequestOrigin origin, BudgetMeter meter,
            AgentGuard guard) {
        this(agent, ceiling, depth, origin, meter, guard, null);
    }

    public static TurnScope of(AgentProfile agent, RequestOrigin origin, java.util.function.LongSupplier nanos) {
        return new TurnScope(agent, agent.ceiling(), 0, origin, new BudgetMeter(agent.budget(), nanos),
                new AgentGuard(nanos));
    }

    /** O sub-agente: teto mínimo, orçamento do pai, disjuntor próprio. */
    public TurnScope child(AgentProfile child, java.util.function.LongSupplier nanos) {
        RiskLevel lower = child.ceiling().compareTo(ceiling) < 0 ? child.ceiling() : ceiling;
        return new TurnScope(child, lower, depth + 1, origin, meter, new AgentGuard(nanos), actor);
    }

    public boolean delegated() {
        return depth > 0;
    }

    public boolean allows(String tool) {
        if (DELEGATE.equals(tool)) {
            // Delegar alcança as ferramentas de outro agente: só quem tem no escopo, e só no turno.
            return depth == 0 && agent.tools().allows(DELEGATE);
        }
        return agent.tools().allows(tool);
    }

    public List<String> pinned() {
        return agent.tools().pinned();
    }
}
