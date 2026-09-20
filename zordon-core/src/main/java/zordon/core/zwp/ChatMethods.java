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
package zordon.core.zwp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import zordon.api.SessionId;
import zordon.api.TurnId;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.chat.ConversationStore;
import zordon.core.chat.StoredMessage;
import zordon.core.chat.TurnManager;

/** Métodos de conversa do ZWP (docs/api/zwp-protocol.md §4). */
public final class ChatMethods {

    /** Teto de mensagens por página de histórico. */
    private static final int MAX_HISTORY_LIMIT = 200;

    private final TurnManager turns;
    private final ConversationStore conversations;

    public ChatMethods(TurnManager turns, ConversationStore conversations) {
        this.turns = turns;
        this.conversations = conversations;
    }

    public void registerOn(ZwpServer server) {
        server.register("chat.send", this::send)
                .register("chat.cancel", this::cancel)
                .register("chat.history", this::history)
                .register("chat.newSession", this::newSession);
    }

    private Map<String, Object> send(ZwpSession session, Map<String, Object> params) {
        String text = text(params);
        SessionId conversation = conversationFrom(params);
        String agent = params.get("agent") instanceof String chosen && !chosen.isBlank() ? chosen : null;
        TurnId turn = turns.send(conversation, text, source(params), agent);
        return Map.of("turnId", turn.value(), "sessionId", conversation.value());
    }

    private Map<String, Object> cancel(ZwpSession session, Map<String, Object> params) {
        String turnId = string(params, "turnId")
                .orElseThrow(() -> new IllegalArgumentException("chat.cancel exige turnId"));
        return Map.of("cancelled", turns.cancel(new TurnId(turnId)));
    }

    private Map<String, Object> history(ZwpSession session, Map<String, Object> params) {
        SessionId conversation = conversationFrom(params);
        int limit = Math.clamp(number(params, "limit").orElse(50L), 1, MAX_HISTORY_LIMIT);
        Optional<Instant> before = string(params, "before").map(Instant::parse);

        List<Map<String, Object>> messages = conversations.history(conversation, before, limit).stream()
                .map(StoredMessage::toWire)
                .toList();
        return Map.of("sessionId", conversation.value(), "messages", messages);
    }

    private Map<String, Object> newSession(ZwpSession session, Map<String, Object> params) {
        return Map.of("sessionId", conversations.newSession(string(params, "title").orElse(null)).value());
    }

    /**
     * Sem {@code sessionId}, usa a conversa corrente — e a cria se não houver
     * nenhuma. Um cliente novo não deveria precisar saber disso para mandar a
     * primeira mensagem.
     */
    private SessionId conversationFrom(Map<String, Object> params) {
        return string(params, "sessionId")
                .map(SessionId::new)
                .filter(conversations::exists)
                .orElseGet(conversations::currentOrNew);
    }

    private String text(Map<String, Object> params) {
        String text = string(params, "text").orElse("").strip();
        if (text.isEmpty()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "chat.send exige um texto não vazio");
        }
        return text;
    }

    private String source(Map<String, Object> params) {
        return string(params, "source").filter(value -> value.equals("voice")).orElse("text");
    }

    private Optional<String> string(Map<String, Object> params, String key) {
        return Optional.ofNullable(params.get(key))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank());
    }

    private Optional<Long> number(Map<String, Object> params, String key) {
        return Optional.ofNullable(params.get(key))
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).longValue());
    }
}
