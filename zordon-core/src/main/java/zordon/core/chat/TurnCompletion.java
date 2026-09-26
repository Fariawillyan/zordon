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

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;
import zordon.ai.AiResponse;
import zordon.ai.ContentBlock;
import zordon.ai.StopReason;
import zordon.api.SessionId;
import zordon.api.TurnId;

/** Como um turno termina: a resposta gravada e publicada, e o aviso de conclusão para a memória. */
final class TurnCompletion {

    private static final Logger log = LoggerFactory.getLogger(TurnManager.class);

    private final ConversationStore conversations;
    private final TurnHooks hooks;
    private final RunningTurns running;
    private final TurnEvents events;

    TurnCompletion(ConversationStore conversations, TurnHooks hooks, RunningTurns running, TurnEvents events) {
        this.conversations = conversations;
        this.hooks = hooks;
        this.running = running;
        this.events = events;
    }

    /**
     * Rota rápida: responde sem chamar modelo nenhum. Mesmo assim publica os
     * eventos do turno — um caminho que não aparece no log é um caminho invisível.
     */
    void answerLocally(SessionId session, TurnId turn, Intent.Immediate immediate) {
        conversations.append(session, new StoredMessage("assistant", immediate.answer(), turn, Instant.now()));
        events.local(turn, immediate.answer(), immediate.rule());
    }

    /** O modelo terminou: o turno sai da lista e a resposta é entregue. */
    void finish(TurnExchange ex, AiResponse response) {
        running.remove(ex.turn());
        boolean tainted = tainted(ex.turn());
        endTools(ex.turn());
        complete(ex, response, tainted);
    }

    /** Termina o turno com o que já havia e o motivo de parar: nunca em silêncio. */
    void stopAtLimit(TurnExchange ex, AiResponse response, String notice) {
        running.remove(ex.turn());
        boolean tainted = tainted(ex.turn());
        endTools(ex.turn());
        String partial = response.text().isBlank() ? notice : response.text() + " " + notice;
        complete(ex, new AiResponse(List.of(new ContentBlock.Text(partial)),
                StopReason.END_TURN, response.usage(), response.cost(), response.model(), response.latency(), null,
                response.isUsageEstimated()), tainted);
    }

    /** Cancelado no meio das ferramentas: sai da lista, fecha as ferramentas e publica. */
    void cancelled(TurnExchange ex) {
        running.remove(ex.turn());
        endTools(ex.turn());
        events.cancelled(ex.turn());
    }

    private boolean tainted(TurnId turn) {
        ToolCaller toolCaller = hooks.caller();
        return toolCaller != null && toolCaller.tainted(turn.value());
    }

    private void endTools(TurnId turn) {
        ToolCaller toolCaller = hooks.caller();
        if (toolCaller != null) {
            toolCaller.endTurn(turn.value());
        }
    }

    private void complete(TurnExchange ex, AiResponse response, boolean tainted) {
        SessionId session = ex.session();
        TurnId turn = ex.turn();
        if (response.stopReason() == StopReason.REFUSAL) {
            // Recusa chega como sucesso HTTP. Tratá-la como resposta vazia produz
            // "o Zordon não respondeu" sem causa aparente.
            events.error(turn, AiException.Kind.INVALID_REQUEST,
                    response.refusal().orElse("O modelo recusou responder."), false);
            return;
        }
        String text = response.text();
        if (!text.isBlank()) {
            conversations.append(session, new StoredMessage("assistant", text, turn, Instant.now()));
        }
        events.response(ex, response, text);

        if (!text.isBlank()) {
            String asked = conversations.conversation(session, PromptComposer.MAX_HISTORY_MESSAGES).stream()
                    .filter(message -> turn.equals(message.turn()) && "user".equals(message.role()))
                    .map(StoredMessage::text).findFirst().orElse("");
            try {
                hooks.completed().accept(new TurnManager.Completed(session, turn, asked, text, tainted));
            } catch (RuntimeException e) {
                log.warn("turno {}: aviso de conclusão falhou: {}", turn.value(), e.getMessage());
            }
        }

        log.info("turno {} concluído por {} em {} ms · {} tokens{} · {}",
                turn.value(), ex.selection().providerId(), response.latency().toMillis(),
                response.usage().totalTokens(), response.isUsageEstimated() ? " (estimado)" : "", response.cost());
    }
}
