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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.CoreUnderTest;
import zordon.zwp.ZwpClient;
import zordon.zwp.ZwpClientListener;
import zordon.zwp.ZwpRemoteException;

/** {@code memory.*} com o núcleo inteiro (SPEC-021). */
class MemoryMethodsTest {

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-021/CA-6")
    @Test
    @SuppressWarnings("unchecked")
    void lembrancaSobreviveAoReinicioESoATelaEsquece() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core)) {
            hello(desktop, ClientKind.DESKTOP);
            desktop.request("chat.send", Map.of("text", "Zordon, anote que o banco da API é Postgres."))
                    .get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (facts(desktop).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(facts(desktop)).hasSize(1);
        }
        assertThat(Files.exists(home.resolve("zordon.db"))).isTrue();

        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core);
                ZwpClient host = client(core)) {
            hello(desktop, ClientKind.DESKTOP);
            hello(host, ClientKind.HOST);
            List<Map<String, Object>> facts = facts(desktop);
            assertThat(facts).singleElement().satisfies(fact -> {
                assertThat(fact).containsEntry("kind", "ENTITY").containsEntry("source", "user");
                assertThat((String) fact.get("content")).isEqualTo("o banco da API é Postgres");
                assertThat((String) fact.get("provenance")).startsWith("t_");
            });
            String id = (String) facts.getFirst().get("id");

            Throwable refused = catchThrowable(() -> host.request("memory.forget", Map.of("factId", id))
                    .get(5, TimeUnit.SECONDS));
            assertThat(((ZwpRemoteException) refused.getCause()).kind()).contains(ZwpErrorKind.ERR_PERMISSION_DENIED);
            assertThat(facts(desktop)).hasSize(1);

            Map<String, Object> hits = desktop.request("memory.search", Map.of("query", "qual banco a API usa?"))
                    .get(5, TimeUnit.SECONDS);
            assertThat((List<?>) hits.get("hits")).hasSize(1);
            Map<String, Object> diagnostics = desktop.request("system.diagnostics", Map.of()).get(5, TimeUnit.SECONDS);
            assertThat((Map<String, Object>) diagnostics.get("memory")).containsEntry("schemaVersion", 8)
                    .containsEntry("facts", 1);

            assertThat(desktop.request("memory.forget", Map.of("factId", id)).get(5, TimeUnit.SECONDS))
                    .containsEntry("forgotten", true);
            assertThat(facts(desktop)).isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> facts(ZwpClient client) throws Exception {
        return (List<Map<String, Object>>) client.request("memory.facts", Map.of()).get(5, TimeUnit.SECONDS)
                .get("facts");
    }

    private static ZwpClient client(CoreUnderTest core) {
        return new ZwpClient(URI.create(core.address()), core.endpoint().token(), new ZwpClientListener() {});
    }

    private static void hello(ZwpClient client, ClientKind kind) throws Exception {
        client.connect(HelloParams.of(new ClientInfo(kind, "teste", "0.0.0"), List.of()), Duration.ofSeconds(10));
    }
}
