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

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;
import zordon.ai.AiResponse;
import zordon.ai.StopReason;
import zordon.api.TokenUsage;
import zordon.api.TurnId;
import zordon.api.event.EventType;
import zordon.core.event.ZordonEventBus;

/**
 * Tudo o que acontece no turno vira evento no barramento. A tela de chat, a de
 * logs e a auditoria são todas projeções do mesmo fluxo.
 */
final class TurnEvents {

    private static final Logger log = LoggerFactory.getLogger(TurnManager.class);

    private final ZordonEventBus bus;

    TurnEvents(ZordonEventBus bus) {
        this.bus = bus;
    }

    ZordonEventBus bus() {
        return bus;
    }

    void userCommand(TurnId turn, String session, String text, String source) {
        bus.publish(EventType.USER_COMMAND, Map.of(
                "turnId", turn.value(), "sessionId", session, "text", text, "source", source));
    }

    /** A rota rápida também publica: um caminho que não aparece no log é um caminho invisível. */
    void local(TurnId turn, String answer, String rule) {
        bus.publish(EventType.AI_RESPONSE, Map.of(
                "turnId", turn.value(),
                "text", answer,
                "done", true,
                "route", "fast:" + rule,
                "usage", usageOf(TokenUsage.NONE, false),
                "costUsd", "0"));
    }

    void thinking(TurnExchange ex) {
        RunningTurn handle = ex.handle();
        Map<String, Object> thinking = new HashMap<>(Map.of(
                "turnId", ex.turn().value(),
                "model", ex.selection().choice().model(),
                "provider", ex.selection().providerId(),
                "agentId", handle.scope == null ? ex.intent().agentId() : handle.scope.agent().id()));
        if (handle.notice != null) {
            thinking.put("notice", handle.notice);
        }
        if (ex.fallback() != null) {
            thinking.put("fallbackFrom", ex.fallback().fromProvider());
            thinking.put("reason", ex.fallback().reason());
            log.warn("turno {}: reserva {} assumiu no lugar de {} — {}",
                    ex.turn().value(), ex.selection().providerId(), ex.fallback().fromProvider(),
                    ex.fallback().reason());
        }
        bus.publish(EventType.AI_THINKING, thinking);
    }

    void response(TurnExchange ex, AiResponse response, String text) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("turnId", ex.turn().value());
        payload.put("text", text);
        payload.put("done", true);
        payload.put("provider", ex.selection().providerId());
        payload.put("model", response.model());
        payload.put("stopReason", response.stopReason().name());
        payload.put("usage", usageOf(response.usage(), response.isUsageEstimated()));
        payload.put("costUsd", response.cost().amount().toPlainString());
        payload.put("latencyMs", response.latency().toMillis());
        if (ex.fallback() != null) {
            payload.put("fallbackFrom", ex.fallback().fromProvider());
            payload.put("fallbackReason", ex.fallback().reason());
        }
        bus.publish(EventType.AI_RESPONSE, payload);
    }

    void cancelled(TurnId turn) {
        bus.publish(EventType.AI_RESPONSE, Map.of(
                "turnId", turn.value(), "text", "", "done", true, "stopReason", StopReason.CANCELLED.name()));
    }

    void error(TurnId turn, AiException.Kind kind, String message, boolean retryable) {
        bus.publish(EventType.AI_ERROR, Map.of(
                "turnId", turn.value(), "kind", kind.name(), "message", message, "retryable", retryable));
    }

    /**
     * O uso de cache é publicado desde o primeiro turno de propósito: leitura de
     * cache sempre zero é o sintoma do maior risco do M1, e um número que ninguém
     * vê não é um número. Estimativa vai marcada: exibida como medida, seria mentira.
     */
    private static Map<String, Object> usageOf(TokenUsage usage, boolean estimated) {
        return Map.of(
                "inputTokens", usage.inputTokens(),
                "outputTokens", usage.outputTokens(),
                "cacheCreationTokens", usage.cacheCreationTokens(),
                "cacheReadTokens", usage.cacheReadTokens(),
                "estimated", estimated);
    }
}
