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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.ZwpErrorKind;
import zordon.api.zwp.ZwpNotification;
import zordon.api.zwp.ZwpRequest;
import zordon.zwp.BinaryFrameCodec;
import zordon.zwp.ZwpCodec;

/** As sessões conectadas e o que sai para elas: notificações, eventos, frames binários e pedidos ao cliente. */
final class ZwpConnections {

    private static final Logger log = LoggerFactory.getLogger(ZwpServer.class);

    private final ZwpCodec codec = new ZwpCodec();
    private final Map<WebSocket, ZwpSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> connectionsById = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("zwp-client-request-deadlines").daemon().factory());

    /** Uma conexão autenticada vira sessão. */
    ZwpSession open(WebSocket connection) {
        ZwpSession session = new ZwpSession(
                "s_" + Long.toHexString(System.nanoTime()),
                event -> send(connection, codec.encode(codec.asNotification(event))));
        sessions.put(connection, session);
        connectionsById.put(session.id(), connection);
        return session;
    }

    ZwpSession session(WebSocket connection) {
        return sessions.get(connection);
    }

    /** A conexão fechou: quem esperava resposta dela não vai recebê-la. @return a sessão, ou {@code null} */
    ZwpSession close(WebSocket connection) {
        ZwpSession session = sessions.remove(connection);
        if (session != null) {
            connectionsById.remove(session.id());
            session.failPending(new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, "cliente desconectou"));
        }
        return session;
    }

    int count() {
        return sessions.size();
    }

    boolean notify(String sessionId, String method, Map<String, Object> params) {
        WebSocket connection = connectionsById.get(sessionId);
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        send(connection, codec.encode(new ZwpNotification(method, params)));
        return true;
    }

    boolean sendBinary(String sessionId, BinaryFrame frame) {
        WebSocket connection = connectionsById.get(sessionId);
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        try {
            connection.send(BinaryFrameCodec.encode(frame));
            return true;
        } catch (RuntimeException e) {
            log.debug("frame binário não enviado à sessão {}: {}", sessionId, e.toString());
            return false;
        }
    }

    /**
     * Pede {@code method} ao cliente da sessão. A resposta só é aceita da mesma
     * conexão, com o mesmo id e dentro do prazo (SPEC-006 §10).
     */
    CompletableFuture<Map<String, Object>> request(String sessionId, String method, Map<String, Object> params,
            Duration timeout) {
        WebSocket connection = connectionsById.get(sessionId);
        ZwpSession session = connection == null ? null : sessions.get(connection);
        if (session == null || !connection.isOpen()) {
            return CompletableFuture.failedFuture(new ZwpMethodException(
                    ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, "cliente não está conectado"));
        }
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        long id = session.expect(answer);
        deadlines.schedule(() -> {
            session.forget(id);
            answer.completeExceptionally(new ZwpMethodException(
                    ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE,
                    method + " sem resposta em " + timeout.toMillis() + " ms"));
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        send(connection, codec.encode(new ZwpRequest(id, method, params)));
        return answer;
    }

    /** Entrega um evento aos clientes que assinaram o tópico dele. */
    void broadcast(EventEnvelope event) {
        sessions.values().forEach(session -> session.deliver(event));
    }

    void send(WebSocket connection, String text) {
        try {
            connection.send(text);
        } catch (RuntimeException e) {
            log.debug("cliente saiu antes de receber a mensagem: {}", e.toString());
        }
    }
}
