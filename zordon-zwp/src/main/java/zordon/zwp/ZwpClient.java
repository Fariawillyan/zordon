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
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ZwpProtocol;

/**
 * Cliente ZWP: uma conexão, já autenticada, com correlação de requisição e
 * resposta.
 *
 * <p>Não tenta reconectar — quem decide isso é {@link CoreConnection}. Separar as
 * duas coisas mantém esta classe testável sem esperar backoff.
 *
 * <p>O que sai fica em {@link ZwpOutbox}; o que chega, em {@link ZwpInbox}.
 */
@Spec("SPEC-002")
public final class ZwpClient implements AutoCloseable {

    private final ZwpClientListener listener;
    private final Socket socket;
    private final ZwpOutbox outbox;
    private final ZwpInbox inbox;

    public ZwpClient(URI endpoint, String token, ZwpClientListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.socket = new Socket(endpoint, Map.of("Authorization", "Bearer " + Objects.requireNonNull(token)));
        this.outbox = new ZwpOutbox(socket::send);
        this.inbox = new ZwpInbox(outbox, listener, text -> {
            if (socket.isOpen()) {
                socket.send(text);
            }
        });
    }

    /** Abre o transporte, já autenticado, sem apresentar-se ainda. */
    public void open(Duration timeout) throws InterruptedException {
        socket.open(timeout);
    }

    /** Apresenta-se ao núcleo. Precisa ser a primeira mensagem da conexão. */
    public HelloResult hello(HelloParams hello, Duration timeout) {
        return outbox.hello(hello, timeout);
    }

    /** Conecta e faz o {@code session.hello}. */
    public HelloResult connect(HelloParams params, Duration timeout) throws InterruptedException {
        open(timeout);
        return hello(params, timeout);
    }

    /**
     * Atende {@code method} quando o núcleo o requisitar. Registre antes de
     * {@link #connect}: o núcleo pode pedir logo depois do hello.
     */
    public ZwpClient handle(String method, RequestHandler handler) {
        inbox.handle(Objects.requireNonNull(method, "method"), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Recebe a notificação {@code method} do núcleo (ex.: {@code audio.credit}). */
    public ZwpClient onNotification(String method, Consumer<Map<String, Object>> handler) {
        inbox.onNotification(Objects.requireNonNull(method, "method"), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Envia um frame binário (ZWP §7). @return se o socket estava aberto para enviar. */
    public boolean sendBinary(BinaryFrame frame) {
        return socket.sendFrame(frame);
    }

    public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
        return outbox.request(method, params);
    }

    public boolean isOpen() {
        return socket.isOpen();
    }

    @Override
    public void close() {
        socket.close();
        outbox.close();
    }

    /** Adapta a biblioteca de WebSocket sem expor o tipo dela nesta API. */
    private final class Socket extends WebSocketClient {

        private static final Logger log = LoggerFactory.getLogger(ZwpClient.class);

        private Socket(URI endpoint, Map<String, String> headers) {
            super(endpoint, new Draft_6455(), headers, 0);
            setConnectionLostTimeout((int) ZwpProtocol.DEFAULT_HEARTBEAT.toSeconds()
                    * ZwpProtocol.MISSED_HEARTBEATS_BEFORE_CLOSE);
        }

        void open(Duration timeout) throws InterruptedException {
            if (!connectBlocking(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ZwpConnectionException("não foi possível conectar em " + getURI());
            }
        }

        boolean sendFrame(BinaryFrame frame) {
            if (!isOpen()) {
                return false;
            }
            try {
                send(BinaryFrameCodec.encode(frame));
                return true;
            } catch (RuntimeException e) {
                log.debug("frame binário não enviado: {}", e.toString());
                return false;
            }
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            log.info("conectado ao núcleo em {}", getURI());
        }

        @Override
        public void onMessage(String message) {
            inbox.receive(message);
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
            try {
                listener.onBinary(BinaryFrameCodec.decode(bytes));
            } catch (ZwpCodecException e) {
                log.warn("frame binário inválido descartado: {}", e.getMessage());
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            outbox.failAll();
            listener.onClosed(code, reason);
        }

        @Override
        public void onError(Exception e) {
            log.debug("erro na conexão ZWP: {}", e.toString());
        }
    }
}
