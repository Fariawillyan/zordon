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
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
import zordon.core.chat.PromptComposer;
import zordon.core.chat.RoleModels;
import zordon.core.chat.ToolCaller;
import zordon.core.event.ZordonEventBus;

/** O laço de uma execução: pergunta ao modelo, roda as ferramentas que ele pedir e devolve o resultado. */
final class AgentLoop {

    static final int MAX_OUTPUT_TOKENS = 8_192;

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final ProviderRegistry providers;
    private final PromptComposer prompts;
    private final ToolCaller tools;
    private final ZordonEventBus bus;

    AgentLoop(ProviderRegistry providers, PromptComposer prompts, ToolCaller tools, ZordonEventBus bus) {
        this.providers = providers;
        this.prompts = prompts;
        this.tools = tools;
        this.bus = bus;
    }

    AgentRunner.Result run(TurnScope scope, String task, String runId, BooleanSupplier cancelled,
            List<String> evidence) {
        AgentProfile agent = scope.agent();
        AgentToolCalls toolCalls = new AgentToolCalls(tools, scope, runId, cancelled, evidence);
        List<AiMessage> messages = new ArrayList<>(
                List.of(new AiMessage(Role.USER, List.of(new ContentBlock.Text(task)))));
        String partial = "";
        while (true) {
            if (cancelled.getAsBoolean()) {
                return new AgentRunner.Result(false, partial, "cancelled");
            }
            String over = scope.meter().step();
            if (over != null) {
                return limit(agent, partial, over);
            }
            ProviderRegistry.Selection selection =
                    RoleModels.first(providers, agent.role(), ModelRole.CONVERSATION).orElse(null);
            if (selection == null) {
                return new AgentRunner.Result(false, partial, "nenhum modelo disponível para o agente " + agent.id());
            }
            AiResponse response;
            try {
                response = ask(selection, agent, task, scope, messages, runId);
            } catch (AiException e) {
                return new AgentRunner.Result(false, partial, e.getMessage());
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
                return new AgentRunner.Result(true, response.text(), "done");
            }
            String callsOver = scope.meter().calls(calls.size());
            if (callsOver != null) {
                return limit(agent, partial, callsOver);
            }
            bus.publish(EventType.AGENT_PROGRESS, Map.of("runId", runId, "step", scope.meter().steps(),
                    "note", "usando " + String.join(", ", calls.stream().map(ContentBlock.ToolUse::tool).toList())));
            List<ContentBlock> results = new ArrayList<>();
            AgentRunner.Result stop = toolCalls.run(calls, results, partial);
            if (stop != null) {
                return stop;
            }
            messages.add(new AiMessage(Role.ASSISTANT, response.content()));
            messages.add(new AiMessage(Role.USER, results));
        }
    }

    private AiResponse ask(ProviderRegistry.Selection selection, AgentProfile agent, String task, TurnScope scope,
            List<AiMessage> messages, String runId) throws AiException {
        List<ToolSpec> offered = tools.offer(task, scope);
        log.debug("agente {} ({}): oferecidas {}", agent.id(), runId,
                offered.stream().map(ToolSpec::name).toList());
        return selection.provider().chat(AiRequest.builder(selection.choice().model())
                .systemPrompt(prompts.systemPrompt() + "\n\n" + agent.prompt())
                .tools(offered)
                .messages(List.copyOf(messages))
                .effort(selection.choice().effortIfAny().orElse(null))
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .timeout(Duration.ofMinutes(5))
                .build());
    }

    private static AgentRunner.Result limit(AgentProfile agent, String partial, String what) {
        String notice = AgentRunner.limitNotice(agent.id(), what);
        return new AgentRunner.Result(false, partial.isBlank() ? notice : partial + " " + notice, "limit:" + what);
    }
}
