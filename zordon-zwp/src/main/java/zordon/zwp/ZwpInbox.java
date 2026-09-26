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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpMessage;
import zordon.api.zwp.ZwpNotification;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;

/**
 * O que chega do núcleo: respostas às nossas requisições, eventos,
 * notificações e requisições que o núcleo faz ao cliente.
 */
final class ZwpInbox {

    private static final Logger log = LoggerFactory.getLogger(ZwpClient.class);

    private final ZwpCodec codec = new ZwpCodec();
    private final ZwpOutbox outbox;
    private final ZwpClientListener listener;
    private final Consumer<String> reply;
    private final Map<String, RequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Map<String, Object>>> notifications = new ConcurrentHashMap<>();

    /** @param reply envia a resposta a uma requisição do núcleo, se o socket ainda estiver aberto */
    ZwpInbox(ZwpOutbox outbox, ZwpClientListener listener, Consumer<String> reply) {
        this.outbox = outbox;
        this.listener = listener;
        this.reply = reply;
    }

    void handle(String method, RequestHandler handler) {
        handlers.put(method, handler);
    }

    void onNotification(String method, Consumer<Map<String, Object>> handler) {
        notifications.put(method, handler);
    }

    void receive(String text) {
        ZwpMessage message;
        try {
            message = codec.decode(text);
        } catch (ZwpCodecException e) {
            log.warn("mensagem ZWP inválida descartada: {}", e.getMessage());
            return;
        }
        switch (message) {
            case ZwpResponse response -> outbox.settle(response);
            case ZwpNotification notification -> dispatch(notification);
            // Fora da thread do socket: um tratador lento não atrasa eventos nem respostas.
            case ZwpRequest request -> Thread.ofVirtual().name("zwp-handler-" + request.method())
                    .start(() -> reply.accept(codec.encode(answer(request))));
        }
    }

    private ZwpResponse answer(ZwpRequest request) {
        RequestHandler handler = handlers.get(request.method());
        if (handler == null) {
            return ZwpResponse.failed(request.id(), ZwpError.protocol(
                    ZwpError.METHOD_NOT_FOUND, "método desconhecido: " + request.method()));
        }
        try {
            return ZwpResponse.ok(request.id(), handler.handle(request.params()));
        } catch (ZwpRemoteException e) {
            return ZwpResponse.failed(request.id(), e.error());
        } catch (Exception e) {
            log.warn("falha ao atender {} do núcleo: {}", request.method(), e.toString());
            return ZwpResponse.failed(request.id(), ZwpError.protocol(
                    ZwpError.INTERNAL_ERROR, "falha ao atender " + request.method()));
        }
    }

    private void dispatch(ZwpNotification notification) {
        if (!ZwpNotification.EVENT_METHOD.equals(notification.method())) {
            Consumer<Map<String, Object>> handler = notifications.get(notification.method());
            if (handler == null) {
                log.debug("notificação ignorada: {}", notification.method());
            } else {
                handler.accept(notification.params());
            }
            return;
        }
        Map<String, Object> params = notification.params();
        try {
            listener.onEvent(new EventEnvelope(
                    ((Number) params.get("seq")).longValue(),
                    Instant.parse((String) params.get("ts")),
                    EventType.valueOf((String) params.get("type")),
                    asPayload(params.get("payload"))));
        } catch (RuntimeException e) {
            // Evento de um núcleo mais novo. Ignorar o desconhecido é requisito de
            // evolução do protocolo (ZWP §11).
            log.debug("evento não reconhecido descartado: {}", params.get("type"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPayload(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
