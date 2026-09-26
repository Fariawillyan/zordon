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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import zordon.api.zwp.BinaryFrame;

/**
 * A conexão aberta agora, se houver, e o que toda conexão nova recebe: os
 * tratadores de requisição e de notificação registrados pela aplicação.
 */
final class ActiveClient {

    private final AtomicReference<ZwpClient> current = new AtomicReference<>();
    private final Map<String, RequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Map<String, Object>>> notifications = new ConcurrentHashMap<>();

    void handle(String method, RequestHandler handler) {
        handlers.put(method, handler);
    }

    void onNotification(String method, Consumer<Map<String, Object>> handler) {
        notifications.put(method, handler);
    }

    /** Liga ao candidato tudo o que foi registrado — antes do hello, porque o núcleo pode pedir logo depois. */
    void bind(ZwpClient candidate) {
        handlers.forEach(candidate::handle);
        notifications.forEach(candidate::onNotification);
    }

    void set(ZwpClient client) {
        current.set(client);
    }

    void clear() {
        current.set(null);
    }

    void close() {
        Optional.ofNullable(current.getAndSet(null)).ifPresent(ZwpClient::close);
    }

    boolean sendBinary(BinaryFrame frame) {
        ZwpClient client = current.get();
        return client != null && client.sendBinary(frame);
    }

    /** Falha rápido quando não há conexão. */
    CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
        ZwpClient client = current.get();
        if (client == null || !client.isOpen()) {
            return CompletableFuture.failedFuture(new ZwpConnectionException("núcleo offline"));
        }
        return client.request(method, params);
    }
}
