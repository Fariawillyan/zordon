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
package zordon.ai.contract;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Servidor HTTP falso, na porta local, que responde com roteiros gravados.
 *
 * <p>É o que permite testar os adaptadores de verdade — HTTP, streaming, erros,
 * cancelamento — sem rede externa e sem gastar crédito
 * ([Testes §11](../../../../../../docs/testing/strategy.md#11-casos-de-erro)).
 */
final class FakeBackend implements AutoCloseable {

    /** Como responder a uma requisição. */
    @FunctionalInterface
    interface Script {
        void respond(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final AtomicReference<Script> script = new AtomicReference<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    private FakeBackend(HttpServer server) {
        this.server = server;
    }

    static FakeBackend start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeBackend backend = new FakeBackend(server);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            backend.requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            backend.authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            try {
                backend.script.get().respond(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return backend;
    }

    /** Uma porta em que nada escuta: o jeito honesto de simular "servidor fora do ar". */
    static URI unreachable() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void respondWith(Script next) {
        script.set(next);
    }

    int requestCount() {
        return requestBodies.size();
    }

    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    /** O cabeçalho Authorization de cada requisição; "null" quando não veio. */
    List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    /** Server-Sent Events, um por vez, com flush entre eles — como um streaming real. */
    static Script sse(List<String> events) {
        return exchange -> {
            writeEvents(exchange, events);
        };
    }

    /** Manda o começo e fica pendurado, como um modelo pensando devagar. */
    Script sseThenHang(List<String> events) {
        return exchange -> {
            OutputStream body = writeEvents(exchange, events);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            body.close();
        };
    }

    static Script error(int status, String json) {
        return exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // Espera mínima: os dois adaptadores tentam de novo em 429 e 5xx, e o
            // teste não precisa esperar o backoff real para provar isso.
            exchange.getResponseHeaders().add("retry-after", "0");
            exchange.getResponseHeaders().add("retry-after-ms", "1");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        };
    }

    private static OutputStream writeEvents(HttpExchange exchange, List<String> events) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = exchange.getResponseBody();
        for (String event : events) {
            body.write((event + "\n\n").getBytes(StandardCharsets.UTF_8));
            body.flush();
        }
        return body;
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
    }
}
