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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpErrorKind;
import zordon.api.zwp.ZwpProtocol;
import zordon.core.StartId;
import zordon.core.event.ZordonEventBus;
import zordon.zwp.ZwpClient;
import zordon.zwp.ZwpClientListener;

/** O núcleo pedindo a um cliente, por WebSocket de verdade (SPEC-006 §3). */
class ClientRequestsTest {

    private static final String TOKEN = "token-de-teste";

    private ZordonEventBus bus;
    private ZwpServer server;
    private final List<ZwpSession> ready = new CopyOnWriteArrayList<>();
    private final List<ZwpSession> closed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws InterruptedException {
        bus = new ZordonEventBus(StartId.generate());
        server = new ZwpServer(new InetSocketAddress("127.0.0.1", 0), TOKEN);
        new SessionMethods(bus, "0.0.0-test", Instant.now(), List.of()).registerOn(server);
        server.onSessionReady(ready::add).onSessionClosed(closed::add);
        server.start();
        assertThat(server.awaitListening(Duration.ofSeconds(10))).isTrue();
    }

    @AfterEach
    void stop() throws InterruptedException {
        server.stop(1000);
        bus.close();
    }

    @AcceptanceCriteria("SPEC-006/CA-11")
    @Test
    void clienteComTratadorRespondeAoPedidoDoNucleo() throws Exception {
        try (ZwpClient host = client()) {
            host.handle("audio.setCaptureEnabled", params -> Map.of("enabled", params.get("enabled")));
            String session = hello(host, ClientKind.HOST, List.of("audio.capture"));

            Map<String, Object> answer = server.request(
                    session, "audio.setCaptureEnabled", Map.of("enabled", false), Duration.ofSeconds(2))
                    .get(5, TimeUnit.SECONDS);

            assertThat(answer).containsEntry("enabled", false);
        }
    }

    @AcceptanceCriteria("SPEC-006/CA-11")
    @Test
    void metodoSemTratadorRespondeMetodoDesconhecido() throws Exception {
        try (ZwpClient host = client()) {
            String session = hello(host, ClientKind.HOST, List.of());

            CompletableFuture<Map<String, Object>> answer =
                    server.request(session, "audio.naoExiste", Map.of(), Duration.ofSeconds(2));

            assertThat(failure(answer).error().code()).isEqualTo(ZwpError.METHOD_NOT_FOUND);
        }
    }

    @AcceptanceCriteria("SPEC-006/CA-10")
    @Test
    void pedidoSemClienteFalhaNaHora() {
        CompletableFuture<Map<String, Object>> answer =
                server.request("s_inexistente", "audio.setCaptureEnabled", Map.of(), Duration.ofSeconds(2));

        assertThat(answer).isCompletedExceptionally();
        assertThat(failure(answer).error().kind()).isEqualTo(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE);
    }

    @AcceptanceCriteria("SPEC-006/CA-10")
    @Test
    void respostaDepoisDoPrazoEDescartada() throws Exception {
        CompletableFuture<Void> lateAnswerSent = new CompletableFuture<>();
        try (ZwpClient host = client()) {
            host.handle("audio.setCaptureEnabled", params -> {
                Thread.sleep(600);
                lateAnswerSent.complete(null);
                return Map.of("enabled", false);
            });
            String session = hello(host, ClientKind.HOST, List.of("audio.capture"));

            CompletableFuture<Map<String, Object>> answer = server.request(
                    session, "audio.setCaptureEnabled", Map.of("enabled", false), Duration.ofMillis(150));

            assertThat(failure(answer).error().kind()).isEqualTo(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE);
            lateAnswerSent.get(5, TimeUnit.SECONDS);
            // A resposta tardia chega e não acha pedido: nada muda, nada quebra.
            assertThat(host.request("session.ping", Map.of()).get(5, TimeUnit.SECONDS)).containsKey("serverTimeMs");
        }
    }

    @AcceptanceCriteria("SPEC-006/CA-10")
    @Test
    void respostaDeOutraSessaoNaoCompletaOPedido() {
        ZwpSession asked = new ZwpSession("s_a", event -> {});
        ZwpSession other = new ZwpSession("s_b", event -> {});
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        long id = asked.expect(answer);

        assertThat(other.answer(id, Map.of("enabled", false), null)).isFalse();
        assertThat(answer).isNotDone();
        assertThat(asked.answer(id, Map.of("enabled", false), null)).isTrue();
        assertThat(answer).isCompletedWithValue(Map.of("enabled", false));
    }

    @Test
    void desconexaoFalhaOsPedidosPendentesEAvisaOsOuvintes() throws Exception {
        ZwpClient host = client();
        host.handle("audio.setCaptureEnabled", params -> {
            Thread.sleep(5_000);
            return Map.of();
        });
        String session = hello(host, ClientKind.HOST, List.of("audio.capture"));
        CompletableFuture<Map<String, Object>> answer = server.request(
                session, "audio.setCaptureEnabled", Map.of("enabled", true), Duration.ofSeconds(10));

        host.close();

        assertThat(failure(answer).error().kind()).isEqualTo(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE);
        assertThat(ready).extracting(ZwpSession::id).containsExactly(session);
        assertThat(ready.getFirst().capabilities()).containsExactly("audio.capture");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (closed.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(closed).extracting(ZwpSession::id).containsExactly(session);
    }

    private ZwpClient client() {
        return new ZwpClient(
                URI.create("ws://127.0.0.1:" + server.getPort() + ZwpProtocol.PATH), TOKEN, new ZwpClientListener() {});
    }

    private static String hello(ZwpClient client, ClientKind kind, List<String> capabilities) throws Exception {
        return client.connect(HelloParams.of(new ClientInfo(kind, "teste", "0.0.0"), capabilities), Duration.ofSeconds(10))
                .sessionId();
    }

    private static ZwpMethodException failure(CompletableFuture<?> answer) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> answer.get(5, TimeUnit.SECONDS));
        assertThat(thrown).hasCauseInstanceOf(ZwpMethodException.class);
        return (ZwpMethodException) thrown.getCause();
    }

}
