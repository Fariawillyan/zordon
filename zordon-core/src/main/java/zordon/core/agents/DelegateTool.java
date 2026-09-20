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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.security.Gatekeeper;

/**
 * {@code agent.delegate}: um sub-trabalho para outro agente (Agentes §4). O filho
 * gasta do orçamento do pai, tem o teto mínimo dos dois e não delega de novo; o
 * resultado volta como dado e contamina o turno do pai ({@code READ_FS}).
 */
@Spec("SPEC-022")
public final class DelegateTool implements Tool {

    private final AgentRegistry registry;
    private final TurnScopes scopes;
    private final AgentRunner runner;
    private final LongSupplier nanos;
    private final AtomicLong sequence = new AtomicLong();

    public DelegateTool(AgentRegistry registry, TurnScopes scopes, AgentRunner runner, LongSupplier nanos) {
        this.registry = registry;
        this.scopes = scopes;
        this.runner = runner;
        this.nanos = nanos;
    }

    @Override public String name() { return TurnScope.DELEGATE; }

    @Override
    public String description() {
        return "Delega uma tarefa de vários passos a outro agente e recebe o resultado. Agentes: "
                + String.join(", ", registry.list().stream().filter(agent -> !agent.id().equals(AgentRegistry.GENERAL))
                        .map(agent -> agent.id() + " (" + agent.description() + ")").toList())
                + ".";
    }

    @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
    @Override public Set<Effect> effects() { return Set.of(Effect.READ_FS); }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "agent", Map.of("type", "string", "description", "o id do agente"),
                "task", Map.of("type", "string", "description", "a tarefa, completa, com o contexto necessário")),
                "required", List.of("agent", "task"));
    }

    @Override
    public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
        String agent = Tool.text(args, "agent");
        String task = Tool.text(args, "task");
        if (registry.find(agent).isEmpty()) {
            throw new ToolException("não conheço o agente " + agent);
        }
        return new ActionDescriptor(name(), Map.of("agent", agent, "task",
                task.length() > 200 ? task.substring(0, 200) + "…" : task), RiskLevel.GREEN, effects(), List.of(), 1,
                List.of(), "Delegar ao agente " + agent);
    }

    @Override
    public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
        throw new ToolException("delegação só dentro de um turno de agente");
    }

    @Override
    public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args, String turnId) throws Exception {
        TurnScope parent = scopes.get(turnId)
                .orElseThrow(() -> new ToolException("delegação só dentro de um turno de agente"));
        if (parent.delegated()) {
            throw new ToolException("um sub-agente não delega de novo");
        }
        AgentProfile child = registry.find(Tool.text(args, "agent"))
                .orElseThrow(() -> new ToolException("não conheço o agente " + args.get("agent")));
        String runId = turnId + ">" + child.id() + "-" + sequence.incrementAndGet();
        AgentRunner.Result result = runner.run(parent.child(child, nanos), Tool.text(args, "task"), runId, turnId,
                () -> false);
        String text = result.text().isBlank() ? "(sem resposta)" : result.text();
        return ToolResult.of(result.ok() ? "Resposta do agente " + child.id() + ":\n" + text
                : "O agente " + child.id() + " parou (" + result.reason() + "). " + text);
    }
}
