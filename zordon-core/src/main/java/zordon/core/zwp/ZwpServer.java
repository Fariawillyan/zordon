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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.zwp.ZwpCloseCode;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpErrorKind;
import zordon.api.zwp.ZwpMessage;
import zordon.api.zwp.ZwpNotification;
import zordon.api.zwp.ZwpProtocol;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;
import zordon.zwp.ZwpCodec;
import zordon.zwp.ZwpCodecException;
import zordon.api.trace.Spec;

/**
 * O único listener do sistema, e ele fica no WSL
 * (docs/architecture/communication.md §3).
 *
 * <p>Autenticação por token do {@code endpoint.json} e recusa de qualquer handshake
 * com {@code Origin}: navegadores sempre o enviam e não conseguem definir
 * cabeçalho em WebSocket, então uma aba maliciosa não tem como se autenticar
 * (ADR-0006).
 */
@Spec("SPEC-002")
public final class ZwpServer extends WebSocketServer implements ClientRequests, ClientNotifier {

    private static final Logger log = LoggerFactory.getLogger(ZwpServer.class);
    private static final String BEARER = "Bearer ";

    private final ZwpCodec codec = new ZwpCodec();
    private final Map<String, MethodHandler> methods = new ConcurrentHashMap<>();
    private final Map<String, AsyncMethodHandler> asyncMethods = new ConcurrentHashMap<>();
    private final Map<WebSocket, ZwpSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> connectionsById = new ConcurrentHashMap<>();
    private final List<Consumer<ZwpSession>> readyListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ZwpSession>> closedListeners = new CopyOnWriteArrayList<>();
    private volatile BinaryHandler binary;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "zwp-client-request-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch listening = new CountDownLatch(1);
    private final byte[] token;

    public ZwpServer(InetSocketAddress address, String token) {
        super(address);
        this.token = Objects.requireNonNull(token, "token").getBytes(StandardCharsets.UTF_8);
        setReuseAddr(true);
        setConnectionLostTimeout((int) ZwpProtocol.DEFAULT_HEARTBEAT.toSeconds()
                * ZwpProtocol.MISSED_HEARTBEATS_BEFORE_CLOSE);
    }

    /** Registra um método servido a clientes. */
    public ZwpServer register(String method, MethodHandler handler) {
        methods.put(method, handler);
        return this;
    }

    /** Registra um método que responde depois, sem prender a thread do WebSocket. */
    public ZwpServer registerAsync(String method, AsyncMethodHandler handler) {
        asyncMethods.put(method, handler);
        return this;
    }

    /** Chamado quando um cliente termina o {@code session.hello} e já recebeu a resposta. */
    public ZwpServer onSessionReady(Consumer<ZwpSession> listener) {
        readyListeners.add(listener);
        return this;
    }

    /** Quem recebe os frames binários (SPEC-009). Sem ele, frames são descartados. */
    public ZwpServer onBinary(BinaryHandler handler) {
        this.binary = handler;
        return this;
    }

    @Override
    public boolean notify(String sessionId, String method, Map<String, Object> params) {
        WebSocket connection = connectionsById.get(sessionId);
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        send(connection, codec.encode(new ZwpNotification(method, params)));
        return true;
    }

    /** Envia um frame binário a uma sessão (a fala a tocar no host). @return falso se ela não está conectada. */
    public boolean sendBinary(String sessionId, zordon.api.zwp.BinaryFrame frame) {
        WebSocket connection = connectionsById.get(sessionId);
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        try {
            connection.send(zordon.zwp.BinaryFrameCodec.encode(frame));
            return true;
        } catch (RuntimeException e) {
            log.debug("frame binário não enviado à sessão {}: {}", sessionId, e.toString());
            return false;
        }
    }

    /** Chamado quando a conexão de uma sessão se encerra, por qualquer motivo. */
    public ZwpServer onSessionClosed(Consumer<ZwpSession> listener) {
        closedListeners.add(listener);
        return this;
    }

    /**
     * Pede {@code method} ao cliente da sessão. A resposta só é aceita da mesma
     * conexão, com o mesmo id e dentro do prazo (SPEC-006 §10).
     */
    @Override
    public CompletableFuture<Map<String, Object>> request(
            String sessionId, String method, Map<String, Object> params, Duration timeout) {
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

    /** Bloqueia até a porta estar aceitando conexões, para o núcleo só se declarar pronto depois. */
    public boolean awaitListening(java.time.Duration timeout) throws InterruptedException {
        return listening.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Entrega um evento aos clientes que assinaram o tópico dele. */
    public void broadcastEvent(EventEnvelope event) {
        sessions.values().forEach(session -> session.deliver(event));
    }

    public int connectedClients() {
        return sessions.size();
    }

    @Override
    public void onStart() {
        listening.countDown();
        log.info("ZWP escutando em {}{}", getAddress(), ZwpProtocol.PATH);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        if (handshake.hasFieldValue("Origin")) {
            // Provável navegador. Ver R12 em docs/architecture/windows-wsl.md.
            connection.close(ZwpCloseCode.ORIGIN_PRESENT, "Origin não é aceito");
            return;
        }
        if (!ZwpProtocol.PATH.equals(path(handshake))) {
            connection.close(ZwpCloseCode.PROTOCOL_UNSUPPORTED, "caminho desconhecido");
            return;
        }
        if (!authorized(handshake)) {
            connection.close(ZwpCloseCode.UNAUTHORIZED, "token inválido ou ausente");
            return;
        }
        ZwpSession session = new ZwpSession(
                "s_" + Long.toHexString(System.nanoTime()),
                event -> send(connection, codec.encode(codec.asNotification(event))));
        sessions.put(connection, session);
        connectionsById.put(session.id(), connection);
        log.debug("conexão aceita: sessão {}", session.id());
    }

    @Override
    public void onMessage(WebSocket connection, String text) {
        ZwpSession session = sessions.get(connection);
        if (session == null) {
            connection.close(ZwpCloseCode.UNAUTHORIZED, "sessão desconhecida");
            return;
        }
        ZwpMessage message;
        try {
            message = codec.decode(text);
        } catch (ZwpCodecException e) {
            send(connection, codec.encode(ZwpResponse.failed(
                    0, ZwpError.protocol(ZwpError.PARSE_ERROR, e.getMessage()))));
            return;
        }
        if (message instanceof ZwpRequest request && asyncMethods.containsKey(request.method())) {
            dispatchAsync(session, request).thenAccept(response -> send(connection, codec.encode(response)));
        } else if (message instanceof ZwpRequest request) {
            ZwpResponse response = dispatch(session, request);
            send(connection, codec.encode(response));
            Runnable pending = session.takePendingAction();
            if (pending != null) {
                pending.run();
            }
            if ("session.hello".equals(request.method()) && !response.isError()) {
                notifyListeners(readyListeners, session);
            }
        } else if (message instanceof ZwpResponse response) {
            if (!session.answer(response.id(), response.result(), response.error())) {
                log.debug("resposta sem pedido pendente na sessão {}: id={}", session.id(), response.id());
            }
        } else if (message instanceof ZwpNotification notification) {
            log.debug("notificação de cliente ignorada: {}", notification.method());
        }
    }

    @Override
    public void onMessage(WebSocket connection, java.nio.ByteBuffer bytes) {
        ZwpSession session = sessions.get(connection);
        BinaryHandler handler = binary;
        if (session == null || !session.helloCompleted() || handler == null) {
            return;
        }
        zordon.api.zwp.BinaryFrame frame;
        try {
            frame = zordon.zwp.BinaryFrameCodec.decode(bytes);
        } catch (ZwpCodecException e) {
            handler.malformed(session, e.getMessage());
            return;
        }
        handler.frame(session, frame);
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        ZwpSession session = sessions.remove(connection);
        if (session != null) {
            connectionsById.remove(session.id());
            session.failPending(new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, "cliente desconectou"));
            log.debug("sessão {} encerrada ({}): {}", session.id(), code, reason);
            notifyListeners(closedListeners, session);
        }
    }

    @Override
    public void onError(WebSocket connection, Exception e) {
        if (connection == null) {
            log.error("falha no servidor ZWP", e);
            listening.countDown();
        } else {
            log.debug("falha em conexão ZWP: {}", e.toString());
        }
    }

    private ZwpResponse dispatch(ZwpSession session, ZwpRequest request) {
        MethodHandler handler = methods.get(request.method());
        if (handler == null) {
            return ZwpResponse.failed(request.id(), ZwpError.protocol(
                    ZwpError.METHOD_NOT_FOUND, "método desconhecido: " + request.method()));
        }
        if (!session.helloCompleted() && !"session.hello".equals(request.method())) {
            return ZwpResponse.failed(request.id(), ZwpError.of(
                    ZwpErrorKind.ERR_UNAUTHORIZED, "session.hello precisa vir primeiro"));
        }
        try {
            return ZwpResponse.ok(request.id(), handler.handle(session, request.params()));
        } catch (ZwpMethodException e) {
            return ZwpResponse.failed(request.id(), e.error());
        } catch (IllegalArgumentException | ZwpCodecException e) {
            return ZwpResponse.failed(request.id(), ZwpError.of(
                    ZwpErrorKind.ERR_INVALID_ARGUMENT, String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            log.error("falha ao tratar {}", request.method(), e);
            return ZwpResponse.failed(request.id(), ZwpError.protocol(
                    ZwpError.INTERNAL_ERROR, "falha interna ao tratar " + request.method()));
        }
    }

    private CompletableFuture<ZwpResponse> dispatchAsync(ZwpSession session, ZwpRequest request) {
        if (!session.helloCompleted()) {
            return CompletableFuture.completedFuture(ZwpResponse.failed(request.id(), ZwpError.of(
                    ZwpErrorKind.ERR_UNAUTHORIZED, "session.hello precisa vir primeiro")));
        }
        CompletableFuture<Map<String, Object>> result;
        try {
            result = asyncMethods.get(request.method()).handle(session, request.params());
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        return result.handle((value, failure) -> failure == null
                ? ZwpResponse.ok(request.id(), value)
                : ZwpResponse.failed(request.id(), asError(request.method(), failure)));
    }

    private ZwpError asError(String method, Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        return switch (cause) {
            case ZwpMethodException e -> e.error();
            case IllegalArgumentException e -> ZwpError.of(ZwpErrorKind.ERR_INVALID_ARGUMENT, String.valueOf(e.getMessage()));
            default -> {
                log.error("falha ao tratar {}", method, cause);
                yield ZwpError.protocol(ZwpError.INTERNAL_ERROR, "falha interna ao tratar " + method);
            }
        };
    }

    private void notifyListeners(List<Consumer<ZwpSession>> listeners, ZwpSession session) {
        for (Consumer<ZwpSession> listener : listeners) {
            try {
                listener.accept(session);
            } catch (RuntimeException e) {
                log.error("falha em listener de sessão", e);
            }
        }
    }

    private boolean authorized(ClientHandshake handshake) {
        String header = handshake.getFieldValue("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        byte[] offered = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, offered);
    }

    private String path(ClientHandshake handshake) {
        String descriptor = handshake.getResourceDescriptor();
        int query = descriptor.indexOf('?');
        return query < 0 ? descriptor : descriptor.substring(0, query);
    }

    private void send(WebSocket connection, String text) {
        try {
            connection.send(text);
        } catch (RuntimeException e) {
            log.debug("cliente saiu antes de receber a mensagem: {}", e.toString());
        }
    }
}
