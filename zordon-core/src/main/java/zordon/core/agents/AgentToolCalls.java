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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import zordon.ai.ContentBlock;
import zordon.core.chat.ToolCaller;

/**
 * As ferramentas que um agente pede numa execução, e a evidência que o Verifier
 * recebe no lugar da narrativa (SPEC-023): o começo do resultado de cada uma.
 */
final class AgentToolCalls {

    static final long WAIT_SECONDS = 15 * 60;
    static final int EVIDENCE_ITEM = 4_000;
    static final int EVIDENCE_TOTAL = 12_000;

    private final ToolCaller tools;
    private final TurnScope scope;
    private final String runId;
    private final BooleanSupplier cancelled;
    private final List<String> evidence;

    /** @param evidence recebe a evidência de cada chamada, dentro do teto */
    AgentToolCalls(ToolCaller tools, TurnScope scope, String runId, BooleanSupplier cancelled, List<String> evidence) {
        this.tools = tools;
        this.scope = scope;
        this.runId = runId;
        this.cancelled = cancelled;
        this.evidence = evidence;
    }

    /**
     * Roda as ferramentas pedidas. Falha de uma vira resultado de erro, não fim do agente.
     *
     * @param results recebe o resultado de cada chamada, na ordem
     * @return o motivo de parar, ou {@code null} para seguir
     */
    AgentRunner.Result run(List<ContentBlock.ToolUse> calls, List<ContentBlock> results, String partial) {
        for (ContentBlock.ToolUse call : calls) {
            if (cancelled.getAsBoolean()) {
                return new AgentRunner.Result(false, partial, "cancelled");
            }
            try {
                ContentBlock.ToolResult result = tools.call(call, "agent", runId, scope)
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);
                results.add(result);
                keepEvidence(call, result);
            } catch (Exception e) {
                results.add(new ContentBlock.ToolResult(call.callId(), "a ferramenta falhou: " + e.getMessage(),
                        true));
            }
            if (scope.guard().tripped() != null) {
                return new AgentRunner.Result(false, "Parei: a execução do agente " + scope.agent().id()
                        + " foi suspensa (" + scope.guard().tripped() + ").", "suspended");
            }
        }
        return null;
    }

    /** A evidência do Verifier, dentro do teto: o começo do resultado de cada ferramenta. */
    private void keepEvidence(ContentBlock.ToolUse call, ContentBlock.ToolResult result) {
        int used = evidence.stream().mapToInt(String::length).sum();
        if (used >= EVIDENCE_TOTAL) {
            return;
        }
        String item = call.tool() + (result.isError() ? " (erro)" : "") + ": " + result.content();
        evidence.add(item.substring(0, Math.min(item.length(), Math.min(EVIDENCE_ITEM, EVIDENCE_TOTAL - used))));
    }
}
