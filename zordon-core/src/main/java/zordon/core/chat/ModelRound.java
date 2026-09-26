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

import java.util.List;
import zordon.ai.AiMessage;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.StopReason;
import zordon.ai.ToolSpec;
import zordon.core.agents.AgentRunner;
import zordon.core.agents.TurnScope;

/** Uma volta ao modelo. Se ele pedir ferramentas, elas rodam e vem outra volta (SPEC-019). */
final class ModelRound {

    /** O modelo falhou: quem decide se a reserva entra. */
    interface Failure {
        void failed(TurnExchange ex, boolean producedText, Throwable failure);
    }

    private final TurnParts parts;
    private final Failure onFailure;
    private final ToolRound tools;

    ModelRound(TurnParts parts, Failure onFailure) {
        this.parts = parts;
        this.onFailure = onFailure;
        this.tools = new ToolRound(parts.completion(), parts.hooks(), this::step);
    }

    void step(TurnExchange ex, List<AiMessage> messages, List<ToolSpec> offered) {
        TurnListener listener = new TurnListener(parts.events().bus(), ex.turn());
        AiStream stream = ex.selection().provider().stream(parts.prompt().request(ex, messages, offered), listener);
        ex.handle().attach(stream);
        stream.result().whenComplete((response, failure) -> {
            if (failure == null) {
                answered(ex, messages, offered, response);
            } else {
                onFailure.failed(ex, listener.producedText(), failure);
            }
        });
    }

    /** O modelo respondeu: ou o teto parou, ou vêm ferramentas, ou o turno acaba. */
    private void answered(TurnExchange ex, List<AiMessage> messages, List<ToolSpec> offered, AiResponse response) {
        ToolCaller toolCaller = parts.hooks().caller();
        TurnScope scope = ex.handle().scope;
        if (scope != null) {
            scope.meter().tokens(response.usage().inputTokens() + response.usage().outputTokens());
        }
        String exceeded = scope == null ? null : scope.meter().exceeded();
        if (exceeded != null && response.stopReason() == StopReason.TOOL_USE) {
            parts.completion().stopAtLimit(ex, response, AgentRunner.limitNotice(scope.agent().id(), exceeded));
            return;
        }
        if (response.stopReason() == StopReason.TOOL_USE && !response.toolCalls().isEmpty()
                && toolCaller != null && !ex.handle().isCancelled()) {
            tools.use(ex, messages, offered, response, toolCaller);
            return;
        }
        parts.completion().finish(ex, response);
    }
}
