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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloParams;
import zordon.core.CoreUnderTest;
import zordon.zwp.ZwpClient;
import zordon.zwp.ZwpClientListener;

class SystemMethodsTest {

    private static final String FAKE_KEY = "sk-ant-segredo-de-teste-que-nao-pode-vazar";

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-005/CA-11")
    @Test
    @SuppressWarnings("unchecked")
    void devolveVersaoTempoNoArProvidersEPapeis() throws Exception {
        Map<String, Object> diagnostics = diagnostics(Map.of());

        Map<String, Object> core = (Map<String, Object>) diagnostics.get("core");
        assertThat(core).containsKeys("version", "startId", "startedAt", "uptimeSeconds");
        assertThat((Map<String, Object>) diagnostics.get("providers"))
                .containsEntry("anthropic", "indisponível — defina ANTHROPIC_API_KEY (em ~/.zordon/secrets.env para o serviço)");
        Map<String, Object> roles = (Map<String, Object>) diagnostics.get("roles");
        // A conversa é da assinatura; a chave de API só aparece como reserva (SPEC-018 §3).
        Map<String, Object> conversation = (Map<String, Object>) roles.get("conversation");
        assertThat(conversation)
                .containsEntry("provider", "claude")
                .containsEntry("model", "opus")
                .containsEntry("ready", false);
        assertThat(conversation.get("reason").toString()).contains("claude CLI");
        assertThat((Map<String, Object>) roles.get("fallback"))
                .containsEntry("provider", "anthropic")
                .containsEntry("ready", false);
    }

    @AcceptanceCriteria("SPEC-005/CA-12")
    @Test
    void nemAChaveNemOTokenDaSessaoAparecem() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home, Map.of("ANTHROPIC_API_KEY", FAKE_KEY));
                ZwpClient client = client(core)) {
            client.connect(HelloParams.of(new ClientInfo(ClientKind.DESKTOP, "teste", "0.1.0"), List.of()),
                    Duration.ofSeconds(10));

            String answer = client.request("system.diagnostics", Map.of()).join().toString();

            assertThat(answer).contains("configurado").doesNotContain(FAKE_KEY).doesNotContain("segredo-de-teste");
            assertThat(answer).doesNotContain(core.endpoint().token());
        }
    }

    private Map<String, Object> diagnostics(Map<String, String> environment) throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home, environment);
                ZwpClient client = client(core)) {
            client.connect(HelloParams.of(new ClientInfo(ClientKind.DESKTOP, "teste", "0.1.0"), List.of()),
                    Duration.ofSeconds(10));
            return client.request("system.diagnostics", Map.of()).join();
        }
    }

    private ZwpClient client(CoreUnderTest core) {
        return new ZwpClient(URI.create(core.address()), core.endpoint().token(), new ZwpClientListener() {});
    }
}
