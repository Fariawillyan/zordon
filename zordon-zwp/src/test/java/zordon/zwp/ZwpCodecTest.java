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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.CoreInfo;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ProtocolRange;
import zordon.api.zwp.ResumeRequest;
import zordon.api.zwp.ZwpErrorKind;
import zordon.api.zwp.ZwpMessage;
import zordon.api.zwp.ZwpNotification;
import zordon.api.zwp.ZwpRequest;
import zordon.api.zwp.ZwpResponse;
import zordon.api.trace.AcceptanceCriteria;

/**
 * Contrato de serialização do ZWP.
 *
 * <p>As amostras douradas existem para que uma mudança de formato apareça como
 * diff que alguém precisa aprovar, em vez de quebrar o cliente em produção
 * (docs/testing/strategy.md §6).
 */
class ZwpCodecTest {

    private final ZwpCodec codec = new ZwpCodec();

    @AcceptanceCriteria("SPEC-002/CA-12")
    @Test
    void requisicaoDeHelloMantemOFormatoPublicado() {
        HelloParams hello = new HelloParams(
                new ClientInfo(ClientKind.DESKTOP, "zordon-desktop", "0.1.0"),
                ProtocolRange.exactly(1),
                List.of("ui.permission-prompt", "ui.notifications"),
                new ResumeRequest("01J9X2K7QF8ZP3000000000000", 48_210));

        String json = codec.encode(new ZwpRequest(1, "session.hello", codec.toParams(hello)));

        assertThat(json).isEqualTo(golden("session-hello-request.json"));
    }

    @AcceptanceCriteria("SPEC-002/CA-12")
    @Test
    void respostaDeHelloMantemOFormatoPublicado() {
        HelloResult result = new HelloResult(
                1,
                new CoreInfo("0.1.0", "01J9X2K7QF8ZP3000000000000", Instant.parse("2026-09-17T22:31:04Z")),
                "s_7f3a",
                List.of("chat.stream"),
                false,
                10_000,
                50);

        String json = codec.encode(ZwpResponse.ok(1, codec.toParams(result)));

        assertThat(json).isEqualTo(golden("session-hello-response.json"));
    }

    @AcceptanceCriteria("SPEC-002/CA-12")
    @Test
    void eventoMantemOFormatoPublicado() {
        EventEnvelope event = new EventEnvelope(
                48_211,
                Instant.parse("2026-09-17T22:31:07.412Z"),
                EventType.CORE_STARTED,
                Map.of("startId", "01J9X2K7QF8ZP3000000000000", "version", "0.1.0"));

        String json = codec.encode(codec.asNotification(event));

        assertThat(json).isEqualTo(golden("event-core-started.json"));
    }

    @Test
    void erroDeAplicacaoCarregaOKindQueOClienteUsaParaDecidir() {
        String json = codec.encode(ZwpResponse.failed(
                7, zordon.api.zwp.ZwpError.of(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, "nenhum host conectado")));

        ZwpMessage decoded = codec.decode(json);

        assertThat(decoded).isInstanceOfSatisfying(ZwpResponse.class, response -> {
            assertThat(response.isError()).isTrue();
            assertThat(response.error().kind()).isEqualTo(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE);
        });
    }

    @AcceptanceCriteria("SPEC-002/CA-14")
    @Test
    void campoDesconhecidoNaoQuebraOCliente() {
        // Requisito de evolução do protocolo (ZWP §11): um núcleo mais novo pode
        // acrescentar campos, e um cliente antigo precisa continuar funcionando.
        String json = """
                {"jsonrpc":"2.0","id":3,"method":"session.hello",\
                "params":{"client":{"kind":"desktop","name":"x","version":"9"},\
                "protocol":{"min":1,"max":1},"futuro":"valor novo"}}""";

        ZwpRequest request = (ZwpRequest) codec.decode(json);

        assertThat(codec.params(request.params(), HelloParams.class).client().name()).isEqualTo("x");
    }

    @Test
    void envelopeSemVersaoDeJsonRpcEhRecusado() {
        assertThatThrownBy(() -> codec.decode("{\"id\":1,\"method\":\"session.ping\"}"))
                .isInstanceOf(ZwpCodecException.class);
    }

    @Test
    void notificacaoNaoTemIdENaoEsperaResposta() {
        ZwpMessage decoded = codec.decode(codec.encode(new ZwpNotification("event", Map.of("type", "CORE_STARTED"))));

        assertThat(decoded).isInstanceOf(ZwpNotification.class);
    }

    private String golden(String name) {
        try (var stream = getClass().getResourceAsStream("/golden/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
