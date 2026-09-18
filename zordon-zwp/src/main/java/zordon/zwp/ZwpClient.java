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
package zordon.zwp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpMessage;
import zordon.api.zwp.ZwpNotification;
import zordon.api.zwp.ZwpProtocol;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;
import zordon.api.trace.Spec;

/**
 * Cliente ZWP: uma conexão, já autenticada, com correlação de requisição e
 * resposta.
 *
 * <p>Não tenta reconectar — quem decide isso é {@link CoreConnection}. Separar as
 * duas coisas mantém esta classe testável sem esperar backoff.
 */
@Spec("SPEC-002")
public final class ZwpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZwpClient.class);

    private final ZwpCodec codec = new ZwpCodec();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeouts =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "zwp-client-timeouts");
                thread.setDaemon(true);
                return thread;
            });

    private final ZwpClientListener listener;
    private final Socket socket;

    public ZwpClient(URI endpoint, String token, ZwpClientListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.socket = new Socket(endpoint, Map.of("Authorization", "Bearer " + Objects.requireNonNull(token)));
    }

    /** Abre o transporte, já autenticado, sem apresentar-se ainda. */
    public void open(Duration timeout) throws InterruptedException {
        if (!socket.connectBlocking(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new ZwpConnectionException("não foi possível conectar em " + socket.getURI());
        }
    }

    /** Apresenta-se ao núcleo. Precisa ser a primeira mensagem da conexão. */
    public HelloResult hello(HelloParams hello, Duration timeout) {
        Map<String, Object> result = request("session.hello", codec.toParams(hello))
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .join();
        return codec.params(result, HelloResult.class);
    }

    /** Conecta e faz o {@code session.hello}. */
    public HelloResult connect(HelloParams params, Duration timeout) throws InterruptedException {
        open(timeout);
        return hello(params, timeout);
    }

    public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        pending.put(id, answer);
        timeouts.schedule(
                () -> failIfPending(id, new TimeoutException(method + " não respondeu no prazo")),
                ZwpProtocol.DEFAULT_REQUEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
        socket.send(codec.encode(new ZwpRequest(id, method, params)));
        return answer;
    }

    public boolean isOpen() {
        return socket.isOpen();
    }

    @Override
    public void close() {
        socket.close();
        timeouts.shutdownNow();
        pending.keySet().forEach(id -> failIfPending(id, new ZwpConnectionException("conexão encerrada")));
    }

    private void failIfPending(long id, Throwable cause) {
        CompletableFuture<Map<String, Object>> answer = pending.remove(id);
        if (answer != null) {
            answer.completeExceptionally(cause);
        }
    }

    private void handle(String text) {
        ZwpMessage message;
        try {
            message = codec.decode(text);
        } catch (ZwpCodecException e) {
            log.warn("mensagem ZWP inválida descartada: {}", e.getMessage());
            return;
        }
        switch (message) {
            case ZwpResponse response -> complete(response);
            case ZwpNotification notification -> dispatch(notification);
            case ZwpRequest request ->
                // Requisições do núcleo para o cliente (bridge, permissão) chegam no M3.
                socket.send(codec.encode(ZwpResponse.failed(
                        request.id(),
                        ZwpError.protocol(ZwpError.METHOD_NOT_FOUND, "método não suportado: " + request.method()))));
        }
    }

    private void complete(ZwpResponse response) {
        CompletableFuture<Map<String, Object>> answer = pending.remove(response.id());
        if (answer == null) {
            log.warn("resposta sem requisição correspondente: id={}", response.id());
            return;
        }
        if (response.isError()) {
            answer.completeExceptionally(new ZwpRemoteException(response.error()));
        } else {
            answer.complete(response.result());
        }
    }

    private void dispatch(ZwpNotification notification) {
        if (!ZwpNotification.EVENT_METHOD.equals(notification.method())) {
            log.debug("notificação ignorada: {}", notification.method());
            return;
        }
        Map<String, Object> params = notification.params();
        try {
            listener.onEvent(new EventEnvelope(
                    ((Number) params.get("seq")).longValue(),
                    java.time.Instant.parse((String) params.get("ts")),
                    EventType.valueOf((String) params.get("type")),
                    asPayload(params.get("payload"))));
        } catch (RuntimeException e) {
            // Evento de um núcleo mais novo. Ignorar o desconhecido é requisito de
            // evolução do protocolo (ZWP §11).
            log.debug("evento não reconhecido descartado: {}", params.get("type"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asPayload(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** Adapta a biblioteca de WebSocket sem expor o tipo dela nesta API. */
    private final class Socket extends WebSocketClient {

        private Socket(URI endpoint, Map<String, String> headers) {
            super(endpoint, new org.java_websocket.drafts.Draft_6455(), headers, 0);
            setConnectionLostTimeout((int) ZwpProtocol.DEFAULT_HEARTBEAT.toSeconds()
                    * ZwpProtocol.MISSED_HEARTBEATS_BEFORE_CLOSE);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            log.info("conectado ao núcleo em {}", getURI());
        }

        @Override
        public void onMessage(String message) {
            handle(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            pending.keySet().forEach(id -> failIfPending(id, new ZwpConnectionException("conexão encerrada")));
            listener.onClosed(code, reason);
        }

        @Override
        public void onError(Exception e) {
            log.debug("erro na conexão ZWP: {}", e.toString());
        }
    }
}
