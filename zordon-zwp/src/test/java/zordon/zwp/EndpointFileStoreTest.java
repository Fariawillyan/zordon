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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.zwp.EndpointFile;
import zordon.api.zwp.ProtocolRange;
import zordon.api.trace.AcceptanceCriteria;

class EndpointFileStoreTest {

    private final EndpointFileStore store = new EndpointFileStore();

    @TempDir
    Path home;

    @Test
    void escreveELeODescritorCompleto() {
        EndpointFile written = endpoint();

        store.write(home.resolve("endpoint.json"), written);

        assertThat(store.read(home.resolve("endpoint.json"))).hasValue(written);
    }

    @AcceptanceCriteria("SPEC-002/CA-9")
    @Test
    void oTokenNaoFicaLegivelParaOutrosUsuarios() throws Exception {
        Path file = home.resolve("endpoint.json");

        store.write(file, endpoint());

        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }

    @AcceptanceCriteria("SPEC-002/CA-9")
    @Test
    void arquivoPelaMetadeEhTratadoComoAusente() throws Exception {
        Path file = home.resolve("endpoint.json");
        Files.writeString(file, "{\"version\": 1, \"token\": \"trun");

        // Melhor o cliente procurar de novo do que tentar autenticar com um token cortado.
        assertThat(store.read(file)).isEmpty();
    }

    @Test
    void arquivoInexistenteNaoEhErro() {
        assertThat(store.read(home.resolve("nao-existe.json"))).isEmpty();
    }

    @Test
    void escritaSubstituiOArquivoAnteriorDeUmaVez() {
        Path file = home.resolve("endpoint.json");
        store.write(file, endpoint());

        EndpointFile novo = new EndpointFile(
                1,
                "01NOVOSTARTID00000000000000",
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "nat",
                List.of("ws://127.0.0.1:8777/zwp/v1"),
                "outro-token",
                ProtocolRange.exactly(1),
                99);

        store.write(file, novo);

        assertThat(store.read(file)).hasValueSatisfying(
                read -> assertThat(read.token()).isEqualTo("outro-token"));
    }

    private EndpointFile endpoint() {
        return new EndpointFile(
                EndpointFile.CURRENT_VERSION,
                "01J9X2K7QF8ZP3000000000000",
                Instant.parse("2026-09-17T22:31:04Z"),
                "nat",
                List.of("ws://127.0.0.1:8777/zwp/v1", "ws://172.19.3.4:8777/zwp/v1"),
                "token-de-teste",
                ProtocolRange.exactly(1),
                4211);
    }
}
