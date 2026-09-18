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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloParams;
import zordon.core.CoreUnderTest;
import zordon.zwp.ZwpClient;
import zordon.zwp.ZwpClientListener;

/**
 * Conversa completa atravessando o protocolo: cliente real, núcleo real, eventos
 * reais.
 *
 * <p>Usa a rota rápida de propósito — ela exercita o caminho inteiro (método ZWP →
 * roteador → barramento → assinatura por tópico → socket) sem depender de chave de
 * API nem de rede.
 */
class ChatOverZwpTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ClientInfo DESKTOP = new ClientInfo(ClientKind.DESKTOP, "teste", "0.1.0");

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-003/CA-10")
    @Test
    void oClienteRecebeOTurnoInteiroPorEventos() throws Exception {
        List<EventEnvelope> received = new CopyOnWriteArrayList<>();

        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient client = new ZwpClient(
                        URI.create(core.address()), core.endpoint().token(), new ZwpClientListener() {
                            @Override
                            public void onEvent(EventEnvelope event) {
                                received.add(event);
                            }
                        })) {
            client.connect(HelloParams.of(DESKTOP, List.of()), TIMEOUT);
            client.request("session.subscribe", Map.of("topics", List.of(Topic.CHAT))).join();

            Map<String, Object> sent = client.request("chat.send", Map.of("text", "que horas são")).join();

            String turnId = (String) sent.get("turnId");
            assertThat(turnId).isNotBlank();
            await(() -> received.stream().anyMatch(
                    event -> event.type() == EventType.AI_RESPONSE
                            && Boolean.TRUE.equals(event.payload().get("done"))));

            assertThat(received).extracting(EventEnvelope::type)
                    .containsExactly(EventType.USER_COMMAND, EventType.AI_RESPONSE);
            assertThat(received.getFirst().payload()).containsEntry("turnId", turnId);
            assertThat(received.getLast().payload().get("text").toString()).startsWith("São ");
        }
    }

    @AcceptanceCriteria("SPEC-003/CA-2")
    @Test
    void oHistoricoSobreviveAoFechamentoDaJanela() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home)) {
            String sessionId;
            try (ZwpClient first = client(core)) {
                first.connect(HelloParams.of(DESKTOP, List.of()), TIMEOUT);
                Map<String, Object> sent = first.request("chat.send", Map.of("text", "que horas são")).join();
                sessionId = (String) sent.get("sessionId");
                await(() -> historyOf(first, sessionId).size() >= 2);
            }

            // A janela fechou. O núcleo continua de pé — é isso que o marco exige.
            try (ZwpClient second = client(core)) {
                second.connect(HelloParams.of(DESKTOP, List.of()), TIMEOUT);

                List<?> messages = historyOf(second, sessionId);

                assertThat(messages).hasSize(2);
            }
        }
    }

    @AcceptanceCriteria("SPEC-003/CA-11")
    @Test
    void mensagemVaziaEhRecusadaComErroDeArgumento() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient client = client(core)) {
            client.connect(HelloParams.of(DESKTOP, List.of()), TIMEOUT);

            assertThat(client.request("chat.send", Map.of("text", "   ")))
                    .failsWithin(Duration.ofSeconds(5))
                    .withThrowableThat()
                    .withMessageContaining("ERR_INVALID_ARGUMENT");
        }
    }

    private List<?> historyOf(ZwpClient client, String sessionId) {
        Map<String, Object> result =
                client.request("chat.history", Map.of("sessionId", sessionId, "limit", 50)).join();
        return (List<?>) result.get("messages");
    }

    private ZwpClient client(CoreUnderTest core) {
        return new ZwpClient(URI.create(core.address()), core.endpoint().token(), new ZwpClientListener() {});
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("condição não ocorreu em " + TIMEOUT);
    }
}
