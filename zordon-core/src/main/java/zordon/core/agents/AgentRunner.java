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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.event.EventType;
import zordon.api.trace.Spec;
import zordon.core.chat.PromptComposer;
import zordon.core.chat.ToolCaller;
import zordon.core.event.ZordonEventBus;

/**
 * Uma execução de agente sem streaming: a delegação e o {@code agent.run} (SPEC-022).
 * O mesmo laço da SPEC-019, com o orçamento, o teto e o disjuntor do escopo.
 */
@Spec("SPEC-022")
public final class AgentRunner {

    /**
     * Como terminou. {@code reason} é {@code done}, o limite estourado, {@code suspended}, {@code cancelled} ou
     * o erro. {@code evidence} são as saídas das ferramentas, que o Verifier recebe no lugar da narrativa
     * (SPEC-023).
     */
    public record Result(boolean ok, String text, String reason, long tokens, int steps, int calls,
            List<String> evidence) {

        Result(boolean ok, String text, String reason) {
            this(ok, text, reason, 0, 0, 0, List.of());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final ToolCaller tools;
    private final ZordonEventBus bus;
    private final AgentLoop loop;
    private volatile BiConsumer<AgentProfile, String> suspended = (agent, reason) -> { };

    public AgentRunner(ProviderRegistry providers, PromptComposer prompts, ToolCaller tools, ZordonEventBus bus) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.loop = new AgentLoop(Objects.requireNonNull(providers, "providers"),
                Objects.requireNonNull(prompts, "prompts"), tools, bus);
    }

    /** Avisado quando o disjuntor de uma execução abre: vira notificação para o usuário. */
    public AgentRunner onSuspended(BiConsumer<AgentProfile, String> listener) {
        this.suspended = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public Result run(TurnScope scope, String task, String runId, String parentId, BooleanSupplier cancelled) {
        AgentProfile agent = scope.agent();
        long started = System.nanoTime();
        int stepsBefore = scope.meter().steps();
        int callsBefore = scope.meter().calls();
        long tokensBefore = scope.meter().tokens();
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("runId", runId);
        start.put("agent", agent.id());
        start.put("task", task.length() > 200 ? task.substring(0, 200) + "…" : task);
        if (parentId != null) {
            start.put("parent", parentId);
        }
        bus.publish(EventType.AGENT_STARTED, start);
        Result result;
        List<String> evidence = new ArrayList<>();
        try {
            result = loop.run(scope, task, runId, cancelled, evidence);
        } finally {
            tools.endTurn(runId);
        }
        result = new Result(result.ok(), result.text(), result.reason(), scope.meter().tokens() - tokensBefore,
                scope.meter().steps() - stepsBefore, scope.meter().calls() - callsBefore, List.copyOf(evidence));
        long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Map<String, Object> finish = new LinkedHashMap<>();
        finish.put("runId", runId);
        finish.put("agent", agent.id());
        finish.put("ok", result.ok());
        finish.put("reason", result.reason());
        finish.put("durationMs", took);
        finish.put("usage", Map.of("tokens", result.tokens(), "steps", result.steps(), "toolCalls", result.calls()));
        finish.put("text", result.text());
        if (parentId != null) {
            finish.put("parent", parentId);
        }
        bus.publish(EventType.AGENT_FINISHED, finish);
        log.info("agente {} ({}): {} em {} ms · {} passos · {} chamadas · {} tokens", agent.id(), runId,
                result.reason(), took, result.steps(), result.calls(), result.tokens());
        if ("suspended".equals(result.reason())) {
            suspended.accept(agent, scope.guard().tripped());
        }
        return result;
    }

    /** "Parei no limite de passos do agente developer." */
    public static String limitNotice(String agentId, String what) {
        return "Parei no limite de " + what + " do agente " + agentId + ".";
    }
}
