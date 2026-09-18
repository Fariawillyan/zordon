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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import zordon.api.SessionId;

/**
 * Sessões e mensagens da conversa.
 *
 * <p>Em memória, no núcleo: como o núcleo é um serviço residente, fechar e reabrir
 * a janela preserva o histórico, que é o que o marco M1 exige. A persistência em
 * SQLite — que sobrevive ao reinício do núcleo — é do M5, junto com a memória de
 * longo prazo ([Memória](../../../../../docs/specs/memory/design.md)).
 */
public final class ConversationStore {

    /** Teto por sessão. Sem ele, um núcleo de semanas cresce sem limite. */
    private static final int MAX_MESSAGES_PER_SESSION = 2_000;

    private final Map<SessionId, Session> sessions = new ConcurrentHashMap<>();

    private record Session(SessionId id, String title, Instant startedAt, List<StoredMessage> messages) {}

    public SessionId newSession(String title) {
        SessionId id = new SessionId("c_" + Long.toHexString(System.nanoTime()));
        sessions.put(id, new Session(id, title, Instant.now(), new CopyOnWriteArrayList<>()));
        return id;
    }

    /** A sessão corrente, criada na primeira mensagem. */
    public SessionId currentOrNew() {
        return sessions.values().stream()
                .max(Comparator.comparing(Session::startedAt))
                .map(Session::id)
                .orElseGet(() -> newSession(null));
    }

    public boolean exists(SessionId session) {
        return sessions.containsKey(session);
    }

    public void append(SessionId session, StoredMessage message) {
        Session current = sessions.get(session);
        if (current == null) {
            throw new IllegalArgumentException("sessão desconhecida: " + session);
        }
        current.messages().add(message);
        while (current.messages().size() > MAX_MESSAGES_PER_SESSION) {
            current.messages().removeFirst();
        }
    }

    /** Histórico mais recente primeiro, como o ZWP declara. */
    public List<StoredMessage> history(SessionId session, Optional<Instant> before, int limit) {
        Session current = sessions.get(session);
        if (current == null) {
            return List.of();
        }
        List<StoredMessage> selected = new ArrayList<>(current.messages());
        selected.sort(Comparator.comparing(StoredMessage::ts).reversed());
        return selected.stream()
                .filter(message -> before.map(instant -> message.ts().isBefore(instant)).orElse(true))
                .limit(limit)
                .toList();
    }

    /** Ordem cronológica, que é a que o modelo precisa. */
    public List<StoredMessage> conversation(SessionId session, int limit) {
        Session current = sessions.get(session);
        if (current == null) {
            return List.of();
        }
        List<StoredMessage> messages = new ArrayList<>(current.messages());
        return messages.size() <= limit ? messages : messages.subList(messages.size() - limit, messages.size());
    }
}
