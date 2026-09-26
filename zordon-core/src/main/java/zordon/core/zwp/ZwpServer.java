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
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import zordon.api.event.EventEnvelope;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;

/**
 * O único listener do sistema, e ele fica no WSL
 * (docs/architecture/communication.md §3).
 *
 * <p>Autenticação por token do {@code endpoint.json} e recusa de qualquer handshake
 * com {@code Origin}: navegadores sempre o enviam e não conseguem definir
 * cabeçalho em WebSocket, então uma aba maliciosa não tem como se autenticar
 * (ADR-0006).
 *
 * <p>O socket fica em {@link ZwpSocket}; as sessões e o que sai para elas, em
 * {@link ZwpConnections}; o que chega, em {@link ZwpRouter}.
 */
@Spec("SPEC-002")
public final class ZwpServer implements ClientRequests, ClientNotifier {

    private final ZwpConnections connections = new ZwpConnections();
    private final ZwpSocket socket;

    public ZwpServer(InetSocketAddress address, String token) {
        this.socket = new ZwpSocket(address, Objects.requireNonNull(token, "token").getBytes(StandardCharsets.UTF_8),
                connections);
    }

    public void start() {
        socket.start();
    }

    public void stop(int timeout) throws InterruptedException {
        socket.stop(timeout);
    }

    public int getPort() {
        return socket.getPort();
    }

    /** Registra um método servido a clientes. */
    public ZwpServer register(String method, MethodHandler handler) {
        socket.handlers().register(method, handler);
        return this;
    }

    /** Registra um método que responde depois, sem prender a thread do WebSocket. */
    public ZwpServer registerAsync(String method, AsyncMethodHandler handler) {
        socket.handlers().registerAsync(method, handler);
        return this;
    }

    /** Chamado quando um cliente termina o {@code session.hello} e já recebeu a resposta. */
    public ZwpServer onSessionReady(Consumer<ZwpSession> listener) {
        socket.handlers().onReady(listener);
        return this;
    }

    /** Quem recebe os frames binários (SPEC-009). Sem ele, frames são descartados. */
    public ZwpServer onBinary(BinaryHandler handler) {
        socket.handlers().onBinary(handler);
        return this;
    }

    /** Chamado quando a conexão de uma sessão se encerra, por qualquer motivo. */
    public ZwpServer onSessionClosed(Consumer<ZwpSession> listener) {
        socket.handlers().onClosed(listener);
        return this;
    }

    @Override
    public boolean notify(String sessionId, String method, Map<String, Object> params) {
        return connections.notify(sessionId, method, params);
    }

    /** Envia um frame binário a uma sessão (a fala a tocar no host). @return falso se ela não está conectada. */
    public boolean sendBinary(String sessionId, BinaryFrame frame) {
        return connections.sendBinary(sessionId, frame);
    }

    /**
     * Pede {@code method} ao cliente da sessão. A resposta só é aceita da mesma
     * conexão, com o mesmo id e dentro do prazo (SPEC-006 §10).
     */
    @Override
    public CompletableFuture<Map<String, Object>> request(
            String sessionId, String method, Map<String, Object> params, Duration timeout) {
        return connections.request(sessionId, method, params, timeout);
    }

    /** Bloqueia até a porta estar aceitando conexões, para o núcleo só se declarar pronto depois. */
    public boolean awaitListening(Duration timeout) throws InterruptedException {
        return socket.awaitListening(timeout);
    }

    /** Entrega um evento aos clientes que assinaram o tópico dele. */
    public void broadcastEvent(EventEnvelope event) {
        connections.broadcast(event);
    }

    public int connectedClients() {
        return connections.count();
    }
}
