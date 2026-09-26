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
package zordon.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiMessage;
import zordon.ai.AiResponse;
import zordon.ai.ContentBlock;
import zordon.ai.Role;
import zordon.ai.ToolSpec;
import zordon.core.agents.AgentRunner;
import zordon.core.agents.TurnScope;

/**
 * Os pedidos de ferramenta do modelo, um de cada vez, e a volta ao modelo com os
 * resultados. Tetos: 25 chamadas e 15 voltas por turno (SPEC-019 CA-3), ou os do
 * agente do turno.
 */
final class ToolRound {

    /** Uma ferramenta pode esperar os 60 s da autorização e mais o tempo dela. */
    static final long WAIT_SECONDS = 15 * 60;

    /** A próxima volta ao modelo, com os resultados. */
    interface Next {
        void step(TurnExchange ex, List<AiMessage> messages, List<ToolSpec> offered);
    }

    private static final Logger log = LoggerFactory.getLogger(TurnManager.class);

    private final TurnCompletion completion;
    private final TurnHooks hooks;
    private final Next next;

    ToolRound(TurnCompletion completion, TurnHooks hooks, Next next) {
        this.completion = completion;
        this.hooks = hooks;
        this.next = next;
    }

    void use(TurnExchange ex, List<AiMessage> messages, List<ToolSpec> offered, AiResponse response,
            ToolCaller toolCaller) {
        List<ContentBlock.ToolUse> calls = response.toolCalls();
        TurnScope scope = ex.handle().scope;
        if (overLimit(ex, response, scope, calls.size())) {
            return;
        }
        ex.handle().toolCalls += calls.size();
        ex.handle().steps++;
        Thread.ofVirtual().name("zordon-tools-" + ex.turn().value()).start(() -> {
            List<ContentBlock> results = run(ex, calls, scope, toolCaller);
            if (scope != null && scope.guard().tripped() != null && !ex.handle().isCancelled()) {
                suspend(ex, response, scope);
                return;
            }
            if (ex.handle().isCancelled()) {
                completion.cancelled(ex);
                return;
            }
            List<AiMessage> following = new ArrayList<>(messages);
            following.add(new AiMessage(Role.ASSISTANT, response.content()));
            following.add(new AiMessage(Role.USER, results));
            next.step(ex, following, offered);
        });
    }

    /** @return {@code true} quando um teto parou o turno e nada mais deve rodar */
    private boolean overLimit(TurnExchange ex, AiResponse response, TurnScope scope, int calls) {
        if (scope == null) {
            if (ex.handle().toolCalls + calls > TurnManager.MAX_TOOL_CALLS
                    || ex.handle().steps + 1 > TurnManager.MAX_STEPS) {
                completion.stopAtLimit(ex, response, TurnManager.TOOL_LIMIT_NOTICE);
                return true;
            }
            return false;
        }
        String over = scope.meter().calls(calls);
        if (over == null) {
            over = scope.meter().step();   // a volta que vem depois das ferramentas
        }
        if (over == null) {
            return false;
        }
        completion.stopAtLimit(ex, response, AgentRunner.limitNotice(scope.agent().id(), over));
        return true;
    }

    /** Uma ferramenta de cada vez. Falha de uma vira resultado de erro, não fim do turno. */
    private List<ContentBlock> run(TurnExchange ex, List<ContentBlock.ToolUse> calls, TurnScope scope,
            ToolCaller toolCaller) {
        List<ContentBlock> results = new ArrayList<>();
        for (ContentBlock.ToolUse call : calls) {
            if (ex.handle().isCancelled()) {
                break;
            }
            ContentBlock.ToolResult result;
            try {
                result = (scope == null ? toolCaller.call(call, ex.handle().source, ex.turn().value())
                        : toolCaller.call(call, ex.handle().source, ex.turn().value(), scope))
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                result = new ContentBlock.ToolResult(call.callId(), "a ferramenta falhou: " + e.getMessage(), true);
            }
            results.add(result);
            if (scope != null && scope.guard().tripped() != null) {
                break;
            }
        }
        return results;
    }

    /** O disjuntor do agente abriu no meio das ferramentas: para, avisa e explica. */
    private void suspend(TurnExchange ex, AiResponse response, TurnScope scope) {
        String reason = scope.guard().tripped();
        log.warn("turno {}: execução do agente {} suspensa — {}", ex.turn().value(), scope.agent().id(), reason);
        hooks.suspended().accept(scope.agent(), reason);
        completion.stopAtLimit(ex, response, "Parei: a execução do agente " + scope.agent().id()
                + " foi suspensa (" + reason + "). Nada mais será feito até você decidir.");
    }
}
