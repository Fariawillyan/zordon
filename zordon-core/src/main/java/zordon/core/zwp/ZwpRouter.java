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

import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpMessage;
import zordon.api.zwp.ZwpNotification;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;
import zordon.zwp.ZwpCodec;
import zordon.zwp.ZwpCodecException;

/** O que chega de uma sessão: requisições para os métodos, respostas a pedidos nossos e notificações. */
final class ZwpRouter {

    private static final Logger log = LoggerFactory.getLogger(ZwpServer.class);

    private final ZwpCodec codec = new ZwpCodec();
    private final ZwpConnections connections;
    private final ZwpHandlers handlers;

    ZwpRouter(ZwpConnections connections, ZwpHandlers handlers) {
        this.connections = connections;
        this.handlers = handlers;
    }

    void receive(ZwpSession session, WebSocket connection, String text) {
        ZwpMessage message;
        try {
            message = codec.decode(text);
        } catch (ZwpCodecException e) {
            connections.send(connection, codec.encode(ZwpResponse.failed(
                    0, ZwpError.protocol(ZwpError.PARSE_ERROR, e.getMessage()))));
            return;
        }
        if (message instanceof ZwpRequest request && handlers.methods().async(request.method())) {
            handlers.methods().dispatchAsync(session, request)
                    .thenAccept(response -> connections.send(connection, codec.encode(response)));
        } else if (message instanceof ZwpRequest request) {
            ZwpResponse response = handlers.methods().dispatch(session, request);
            connections.send(connection, codec.encode(response));
            Runnable pending = session.takePendingAction();
            if (pending != null) {
                pending.run();
            }
            if ("session.hello".equals(request.method()) && !response.isError()) {
                handlers.ready(session);
            }
        } else if (message instanceof ZwpResponse response) {
            if (!session.answer(response.id(), response.result(), response.error())) {
                log.debug("resposta sem pedido pendente na sessão {}: id={}", session.id(), response.id());
            }
        } else if (message instanceof ZwpNotification notification) {
            log.debug("notificação de cliente ignorada: {}", notification.method());
        }
    }
}
