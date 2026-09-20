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
import java.util.concurrent.CompletableFuture;
import zordon.ai.ContentBlock;
import zordon.ai.ToolSpec;
import zordon.api.trace.Spec;

/**
 * O que o turno usa para oferecer e chamar ferramentas (SPEC-019). A execução
 * de verdade é do {@code SkillRuntime}, pelo {@code Gatekeeper}; o turno não
 * autoriza nada.
 */
@Spec("SPEC-019")
public interface ToolCaller {

    /** No máximo 12 ferramentas para este pedido. */
    List<ToolSpec> offer(String userText);

    /** Executa um pedido do modelo e devolve o resultado para a próxima volta. */
    CompletableFuture<ContentBlock.ToolResult> call(ContentBlock.ToolUse call, String source, String turnId);

    /** O turno acabou: limpa o que era dele (a marca de contaminação). */
    void endTurn(String turnId);

    /** Para um agente: escopo, teto, orçamento e disjuntor (SPEC-022). */
    default List<ToolSpec> offer(String userText, zordon.core.agents.TurnScope scope) {
        return offer(userText);
    }

    default CompletableFuture<ContentBlock.ToolResult> call(ContentBlock.ToolUse call, String source, String turnId,
            zordon.core.agents.TurnScope scope) {
        return call(call, source, turnId);
    }

    /** O turno leu conteúdo externo (SPEC-019 CA-4). Consultado antes de {@link #endTurn}. */
    default boolean tainted(String turnId) {
        return false;
    }
}
