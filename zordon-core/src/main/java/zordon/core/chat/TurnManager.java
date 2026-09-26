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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.SessionId;
import zordon.api.TurnId;
import zordon.api.trace.Spec;
import zordon.core.event.ZordonEventBus;

/**
 * Conduz um turno: da entrada do usuário até a resposta completa.
 *
 * <p>Tudo o que acontece no turno vira evento no barramento. A tela de chat, a de
 * logs e a auditoria são todas projeções do mesmo fluxo — o log de atividades não
 * é uma funcionalidade separada
 * ([Orientação a eventos §2](../../../../../docs/architecture/event-driven.md)).
 *
 * <p>Não conhece fornecedor de modelo: pede ao {@link ProviderRegistry} o provider
 * do papel {@code conversation} e, se ele falhar antes de responder, o do papel
 * {@code fallback} (ADR-0026).
 *
 * <p>A entrada fica em {@link TurnIntake}; a escolha do provider e a reserva, em
 * {@link TurnRouting}; a volta ao modelo, em {@link ModelRound}; as ferramentas,
 * em {@link ToolRound}; o fim do turno, em {@link TurnCompletion}.
 */
@Spec("SPEC-003")
public final class TurnManager {

    /** Tetos do laço de ferramentas (Segurança §8, SPEC-019 CA-3). */
    static final int MAX_TOOL_CALLS = 25;
    static final int MAX_STEPS = 15;
    static final String TOOL_LIMIT_NOTICE = "Parei no limite de ferramentas deste turno.";

    /** Quem executa as ferramentas; sem ele, a rota avisa em vez de sumir. */
    public interface ToolInvoker {
        CompletableFuture<String> invoke(String tool, Map<String, Object> args, String source, String turnId);
    }

    /** O que a memória sabe sobre o pedido, como bloco de dados (SPEC-021 CA-2). Vazio se nada. */
    public interface Recall {
        String about(String userText);
    }

    /** Um turno respondido pelo modelo, para a destilação (SPEC-021 CA-5). */
    public record Completed(SessionId session, TurnId turn, String userText, String answer, boolean tainted) {}

    private final TurnHooks hooks = new TurnHooks();
    private final RunningTurns running = new RunningTurns();
    private final TurnIntake intake;

    public TurnManager(
            ZordonEventBus bus,
            ConversationStore conversations,
            IntentRouter router,
            PromptComposer prompts,
            ProviderRegistry providers) {
        TurnParts parts = TurnParts.of(bus, conversations, prompts, hooks, running);
        this.intake = new TurnIntake(parts, router, new TurnRouting(parts, providers));
    }

    /** As ferramentas, os agentes, a memória e quem ouve o fim do turno. */
    public TurnHooks hooks() {
        return hooks;
    }

    /** Aceita a entrada e devolve imediatamente: a resposta chega por eventos. */
    public TurnId send(SessionId session, String text, String source) {
        return send(session, text, source, null);
    }

    /** @param agent o agente escolhido na tela; forçar sempre vence o roteador (Agentes §6) */
    public TurnId send(SessionId session, String text, String source, String agent) {
        return intake.send(session, text, source, agent);
    }

    /** Cancela um turno específico. Cancelamento não é erro. */
    public boolean cancel(TurnId turn) {
        return running.cancel(turn);
    }

    public boolean isRunning(TurnId turn) {
        return running.contains(turn);
    }

    /** Lista de turnos em andamento, para diagnóstico. */
    public List<String> activeTurns() {
        return running.ids();
    }
}
