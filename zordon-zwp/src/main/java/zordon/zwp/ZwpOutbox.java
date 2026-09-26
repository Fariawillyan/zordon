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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ZwpProtocol;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;

/** As requisições que o cliente envia: numeração, correlação com a resposta e prazo. */
final class ZwpOutbox {

    private static final Logger log = LoggerFactory.getLogger(ZwpClient.class);

    private final ZwpCodec codec = new ZwpCodec();
    private final Consumer<String> wire;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("zwp-client-timeouts").daemon().factory());

    /** @param wire envia o texto pelo socket; lança se ele estiver fechado */
    ZwpOutbox(Consumer<String> wire) {
        this.wire = wire;
    }

    /** Apresenta-se ao núcleo. Precisa ser a primeira mensagem da conexão. */
    HelloResult hello(HelloParams hello, Duration timeout) {
        Map<String, Object> result = request("session.hello", codec.toParams(hello))
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .join();
        return codec.params(result, HelloResult.class);
    }

    CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        pending.put(id, answer);
        timeouts.schedule(
                () -> failIfPending(id, new TimeoutException(method + " não respondeu no prazo")),
                ZwpProtocol.DEFAULT_REQUEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
        wire.accept(codec.encode(new ZwpRequest(id, method, params)));
        return answer;
    }

    /** A resposta de uma requisição nossa. */
    void settle(ZwpResponse response) {
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

    /** A conexão caiu: quem espera resposta não vai recebê-la. */
    void failAll() {
        pending.keySet().forEach(id -> failIfPending(id, new ZwpConnectionException("conexão encerrada")));
    }

    void close() {
        timeouts.shutdownNow();
        failAll();
    }

    private void failIfPending(long id, Throwable cause) {
        CompletableFuture<Map<String, Object>> answer = pending.remove(id);
        if (answer != null) {
            answer.completeExceptionally(cause);
        }
    }
}
