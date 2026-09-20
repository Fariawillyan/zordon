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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.ContentBlock;
import zordon.ai.ModelRole;
import zordon.ai.Role;
import zordon.ai.StopReason;
import zordon.ai.ToolSpec;
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

    static final int MAX_OUTPUT_TOKENS = 8_192;
    static final long TOOL_WAIT_SECONDS = 15 * 60;

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

    static final int EVIDENCE_ITEM = 4_000;
    static final int EVIDENCE_TOTAL = 12_000;

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final ProviderRegistry providers;
    private final PromptComposer prompts;
    private final ToolCaller tools;
    private final ZordonEventBus bus;
    private volatile BiConsumer<AgentProfile, String> suspended = (agent, reason) -> { };

    public AgentRunner(ProviderRegistry providers, PromptComposer prompts, ToolCaller tools, ZordonEventBus bus) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.bus = Objects.requireNonNull(bus, "bus");
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
            result = loop(scope, task, runId, cancelled, evidence);
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

    private Result loop(TurnScope scope, String task, String runId, BooleanSupplier cancelled, List<String> evidence) {
        AgentProfile agent = scope.agent();
        List<AiMessage> messages = new ArrayList<>(List.of(new AiMessage(Role.USER, List.of(new ContentBlock.Text(task)))));
        String partial = "";
        while (true) {
            if (cancelled.getAsBoolean()) {
                return new Result(false, partial, "cancelled");
            }
            String over = scope.meter().step();
            if (over != null) {
                return limit(agent, partial, over);
            }
            ProviderRegistry.Selection selection = selection(agent.role());
            if (selection == null) {
                return new Result(false, partial, "nenhum modelo disponível para o agente " + agent.id());
            }
            List<ToolSpec> offered = tools.offer(task, scope);
            log.debug("agente {} ({}): oferecidas {}", agent.id(), runId, offered.stream().map(ToolSpec::name).toList());
            AiResponse response;
            try {
                response = selection.provider().chat(AiRequest.builder(selection.choice().model())
                        .systemPrompt(prompts.systemPrompt() + "\n\n" + agent.prompt())
                        .tools(offered)
                        .messages(List.copyOf(messages))
                        .effort(selection.choice().effortIfAny().orElse(null))
                        .maxOutputTokens(MAX_OUTPUT_TOKENS)
                        .timeout(Duration.ofMinutes(5))
                        .build());
            } catch (AiException e) {
                return new Result(false, partial, e.getMessage());
            }
            scope.meter().tokens(response.usage().inputTokens() + response.usage().outputTokens());
            if (!response.text().isBlank()) {
                partial = response.text();
            }
            String exceeded = scope.meter().exceeded();
            if (exceeded != null) {
                return limit(agent, partial, exceeded);
            }
            List<ContentBlock.ToolUse> calls = response.toolCalls();
            if (response.stopReason() != StopReason.TOOL_USE || calls.isEmpty()) {
                return new Result(true, response.text(), "done");
            }
            String callsOver = scope.meter().calls(calls.size());
            if (callsOver != null) {
                return limit(agent, partial, callsOver);
            }
            bus.publish(EventType.AGENT_PROGRESS, Map.of("runId", runId, "step", scope.meter().steps(),
                    "note", "usando " + String.join(", ", calls.stream().map(ContentBlock.ToolUse::tool).toList())));
            List<ContentBlock> results = new ArrayList<>();
            for (ContentBlock.ToolUse call : calls) {
                if (cancelled.getAsBoolean()) {
                    return new Result(false, partial, "cancelled");
                }
                try {
                    ContentBlock.ToolResult result = tools.call(call, "agent", runId, scope)
                            .get(TOOL_WAIT_SECONDS, TimeUnit.SECONDS);
                    results.add(result);
                    int used = evidence.stream().mapToInt(String::length).sum();
                    if (used < EVIDENCE_TOTAL) {
                        String item = call.tool() + (result.isError() ? " (erro)" : "") + ": " + result.content();
                        evidence.add(item.substring(0, Math.min(item.length(),
                                Math.min(EVIDENCE_ITEM, EVIDENCE_TOTAL - used))));
                    }
                } catch (Exception e) {
                    results.add(new ContentBlock.ToolResult(call.callId(), "a ferramenta falhou: " + e.getMessage(),
                            true));
                }
                if (scope.guard().tripped() != null) {
                    return new Result(false, "Parei: a execução do agente " + agent.id() + " foi suspensa ("
                            + scope.guard().tripped() + ").", "suspended");
                }
            }
            messages.add(new AiMessage(Role.ASSISTANT, response.content()));
            messages.add(new AiMessage(Role.USER, results));
        }
    }

    private static Result limit(AgentProfile agent, String partial, String what) {
        String notice = limitNotice(agent.id(), what);
        return new Result(false, partial.isBlank() ? notice : partial + " " + notice, "limit:" + what);
    }

    /** "Parei no limite de passos do agente developer." */
    public static String limitNotice(String agentId, String what) {
        return "Parei no limite de " + what + " do agente " + agentId + ".";
    }

    private ProviderRegistry.Selection selection(ModelRole role) {
        for (ModelRole candidate : List.of(role, ModelRole.CONVERSATION)) {
            if (providers.select(candidate) instanceof ProviderRegistry.Resolution.Selected selected) {
                return selected.selection();
            }
        }
        return null;
    }
}
