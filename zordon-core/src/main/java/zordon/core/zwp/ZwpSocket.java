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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.ZwpCloseCode;
import zordon.api.zwp.ZwpProtocol;

/**
 * O listener WebSocket. Autenticação por token do {@code endpoint.json} e recusa
 * de qualquer handshake com {@code Origin}: navegadores sempre o enviam e não
 * conseguem definir cabeçalho em WebSocket, então uma aba maliciosa não tem como
 * se autenticar (ADR-0006).
 */
final class ZwpSocket extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(ZwpServer.class);
    private static final String BEARER = "Bearer ";

    private final byte[] token;
    private final ZwpConnections connections;
    private final ZwpHandlers handlers = new ZwpHandlers();
    private final ZwpRouter router;
    private final CountDownLatch listening = new CountDownLatch(1);

    ZwpSocket(InetSocketAddress address, byte[] token, ZwpConnections connections) {
        super(address);
        this.token = token;
        this.connections = connections;
        this.router = new ZwpRouter(connections, handlers);
        setReuseAddr(true);
        setConnectionLostTimeout((int) ZwpProtocol.DEFAULT_HEARTBEAT.toSeconds()
                * ZwpProtocol.MISSED_HEARTBEATS_BEFORE_CLOSE);
    }

    ZwpHandlers handlers() {
        return handlers;
    }

    /** Bloqueia até a porta estar aceitando conexões, para o núcleo só se declarar pronto depois. */
    boolean awaitListening(Duration timeout) throws InterruptedException {
        return listening.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        ZwpSession session = connections.open(connection);
        log.debug("conexão aceita: sessão {}", session.id());
    }

    @Override
    public void onMessage(WebSocket connection, String text) {
        ZwpSession session = connections.session(connection);
        if (session == null) {
            connection.close(ZwpCloseCode.UNAUTHORIZED, "sessão desconhecida");
            return;
        }
        router.receive(session, connection, text);
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer bytes) {
        handlers.binary(connections.session(connection), bytes);
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        ZwpSession session = connections.close(connection);
        if (session != null) {
            log.debug("sessão {} encerrada ({}): {}", session.id(), code, reason);
            handlers.closed(session);
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

    private boolean authorized(ClientHandshake handshake) {
        String header = handshake.getFieldValue("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        byte[] offered = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, offered);
    }

    private static String path(ClientHandshake handshake) {
        String descriptor = handshake.getResourceDescriptor();
        int query = descriptor.indexOf('?');
        return query < 0 ? descriptor : descriptor.substring(0, query);
    }
}
