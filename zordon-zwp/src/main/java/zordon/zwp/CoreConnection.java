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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import zordon.api.event.EventEnvelope;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.HelloResult;

/**
 * Mantém um cliente conectado ao núcleo: descobre o endereço pelo
 * {@code endpoint.json}, reconecta com backoff e observa o arquivo para reagir
 * imediatamente a um reinício do núcleo (ADR-0006, ZWP §8).
 *
 * <p>Esta classe não sabe nada de interface gráfica, de propósito: ela é o que
 * torna possível testar reconexão sem abrir uma janela.
 *
 * <p>O laço de reconexão fica em {@link ConnectionLoop}; a conexão atual e os
 * tratadores, em {@link ActiveClient}.
 */
@Spec("SPEC-002")
public final class CoreConnection implements AutoCloseable {

    /** Estado visível ao usuário. É o que o tray e a barra de status mostram. */
    public enum State {
        OFFLINE,
        CONNECTING,
        ONLINE
    }

    /** Quem observa a conexão. Chamado sempre fora da thread da interface. */
    public interface Listener {

        /**
         * @param resumed quando falso, o cliente precisa descartar o estado volátil e
         *     recarregar: não houve continuidade (ADR-0011).
         */
        default void onOnline(HelloResult hello, boolean resumed) {}

        default void onOffline(String reason) {}

        default void onEvent(EventEnvelope event) {}

        /** Frame binário do núcleo, como a fala a tocar (ZWP §7). */
        default void onBinary(BinaryFrame frame) {}
    }

    private final ActiveClient active = new ActiveClient();
    private final ConnectionLoop loop;
    private volatile Thread worker;

    public CoreConnection(Path endpointFile, ClientInfo client, List<String> capabilities, Listener listener) {
        this.loop = new ConnectionLoop(new EndpointWatch(Objects.requireNonNull(endpointFile, "endpointFile")),
                new SessionResume(Objects.requireNonNull(client, "client"), capabilities), active,
                Objects.requireNonNull(listener, "listener"));
    }

    public synchronized void start() {
        if (worker == null) {
            worker = Thread.ofVirtual().name("zordon-core-connection").start(loop);
        }
    }

    /** Atende {@code method} vindo do núcleo, nesta conexão e nas próximas. */
    public CoreConnection handle(String method, RequestHandler handler) {
        active.handle(Objects.requireNonNull(method, "method"), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Recebe a notificação {@code method} do núcleo, nesta conexão e nas próximas. */
    public CoreConnection onNotification(String method, Consumer<Map<String, Object>> handler) {
        active.onNotification(Objects.requireNonNull(method, "method"), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Envia um frame binário pela conexão atual. @return falso se offline. */
    public boolean sendBinary(BinaryFrame frame) {
        return active.sendBinary(frame);
    }

    public State state() {
        return loop.state();
    }

    /** Envia uma requisição se houver conexão; falha rápido se não houver. */
    public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
        return active.request(method, params);
    }

    @Override
    public void close() {
        loop.stop();
        active.close();
        Optional.ofNullable(worker).ifPresent(Thread::interrupt);
    }
}
