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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.EndpointFile;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ProtocolRange;
import zordon.api.zwp.ResumeRequest;
import zordon.api.zwp.ZwpProtocol;
import zordon.api.trace.Spec;

/**
 * Mantém um cliente conectado ao núcleo: descobre o endereço pelo
 * {@code endpoint.json}, reconecta com backoff e observa o arquivo para reagir
 * imediatamente a um reinício do núcleo (ADR-0006, ZWP §8).
 *
 * <p>Esta classe não sabe nada de interface gráfica, de propósito: ela é o que
 * torna possível testar reconexão sem abrir uma janela.
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
    }

    private static final Logger log = LoggerFactory.getLogger(CoreConnection.class);
    private static final Duration ENDPOINT_POLL = Duration.ofMillis(250);

    private final Path endpointFile;
    private final ClientInfo client;
    private final List<String> capabilities;
    private final Listener listener;
    private final EndpointFileStore store = new EndpointFileStore();
    private final ReconnectBackoff backoff = new ReconnectBackoff();
    private final AtomicReference<State> state = new AtomicReference<>(State.OFFLINE);
    private final AtomicReference<ZwpClient> active = new AtomicReference<>();

    private volatile boolean running;
    private volatile Thread worker;
    private volatile String lastStartId;
    private volatile long lastEventSeq;

    public CoreConnection(Path endpointFile, ClientInfo client, List<String> capabilities, Listener listener) {
        this.endpointFile = Objects.requireNonNull(endpointFile, "endpointFile");
        this.client = Objects.requireNonNull(client, "client");
        this.capabilities = List.copyOf(capabilities);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("zordon-core-connection").start(this::loop);
    }

    public State state() {
        return state.get();
    }

    /** Envia uma requisição se houver conexão; falha rápido se não houver. */
    public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params) {
        ZwpClient current = active.get();
        if (current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(new ZwpConnectionException("núcleo offline"));
        }
        return current.request(method, params);
    }

    @Override
    public void close() {
        running = false;
        Optional.ofNullable(active.getAndSet(null)).ifPresent(ZwpClient::close);
        Optional.ofNullable(worker).ifPresent(Thread::interrupt);
    }

    private void loop() {
        while (running) {
            Optional<EndpointFile> endpoint = store.read(endpointFile);
            if (endpoint.isEmpty()) {
                goOffline("núcleo não publicou endpoint.json");
                waitBeforeRetry();
                continue;
            }
            if (!tryConnect(endpoint.get())) {
                waitBeforeRetry();
            }
        }
    }

    private boolean tryConnect(EndpointFile endpoint) {
        state.set(State.CONNECTING);
        for (String address : endpoint.endpoints()) {
            if (!running) {
                return true;
            }
            CountDownLatch closed = new CountDownLatch(1);
            try (ZwpClient candidate = new ZwpClient(URI.create(address), endpoint.token(), new ZwpClientListener() {
                @Override
                public void onEvent(EventEnvelope event) {
                    lastEventSeq = Math.max(lastEventSeq, event.seq());
                    listener.onEvent(event);
                }

                @Override
                public void onClosed(int code, String reason) {
                    closed.countDown();
                }
            })) {
                HelloResult hello = candidate.connect(helloFor(endpoint), Duration.ofSeconds(10));
                active.set(candidate);
                state.set(State.ONLINE);
                backoff.reset();
                onHello(hello);
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            } catch (RuntimeException e) {
                log.debug("falha ao conectar em {}: {}", address, e.getMessage());
                continue;
            } finally {
                active.set(null);
            }
            goOffline("conexão encerrada");
            return false;
        }
        goOffline("nenhum endereço do endpoint.json respondeu");
        return false;
    }

    private void onHello(HelloResult hello) {
        boolean sameCore = hello.core().startId().equals(lastStartId);
        boolean resumed = hello.resumed() && sameCore;
        if (!resumed) {
            lastEventSeq = 0;
        }
        lastStartId = hello.core().startId();
        listener.onOnline(hello, resumed);
    }

    private HelloParams helloFor(EndpointFile endpoint) {
        ResumeRequest resume = lastStartId != null && lastEventSeq > 0
                ? new ResumeRequest(lastStartId, lastEventSeq)
                : null;
        return new HelloParams(client, ProtocolRange.exactly(ZwpProtocol.VERSION), capabilities, resume);
    }

    private void goOffline(String reason) {
        if (state.getAndSet(State.OFFLINE) != State.OFFLINE) {
            listener.onOffline(reason);
        }
    }

    /**
     * Espera o backoff, mas acorda assim que o {@code endpoint.json} mudar: um
     * núcleo que acabou de reiniciar não deve esperar dez segundos para ser achado.
     */
    private void waitBeforeRetry() {
        long deadline = System.nanoTime() + backoff.nextDelay().toNanos();
        long seen = lastModified();
        while (running && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(ENDPOINT_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (lastModified() != seen) {
                backoff.reset();
                return;
            }
        }
    }

    private long lastModified() {
        try {
            return Files.exists(endpointFile) ? Files.getLastModifiedTime(endpointFile).toMillis() : -1;
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
