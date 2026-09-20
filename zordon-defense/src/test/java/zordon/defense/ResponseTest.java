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
package zordon.defense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.Severity;
import zordon.api.trace.AcceptanceCriteria;

/** Disjuntor, invariante do evento e o Anel 2 (SPEC-027). */
class ResponseTest {

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-027/CA-5")
    @Test
    void acaoExecutadaSemMensagemAoUsuarioEhImpossivelDeConstruir() {
        assertThatThrownBy(() -> new SecurityEvent("sec-1", Instant.now(), Severity.CRITICAL, "ai.policy-tamper",
                "agent:x", "fnd-1", "abrir o disjuntor", "abrir o disjuntor", "CONTAINED", "AUTO_CONTAINMENT", true,
                null)).hasMessageContaining("ação executada sem mensagem ao usuário");
        assertThatThrownBy(() -> new SecurityEvent("sec-1", Instant.now(), Severity.CRITICAL, "d", "agent:x", "f",
                "p", "p", "CONTAINED", "AUTO_CONTAINMENT", true, "  ")).isInstanceOf(IllegalArgumentException.class);

        SecurityEvent observed = SecurityEvent.observed("sec-2", Instant.now(), Severity.WARNING, "ai.agent-loop",
                "agent:x", "fnd-2", "nenhuma");
        assertThat(observed.executed()).isEqualTo(SecurityEvent.NONE);
        assertThat(observed.userMessageId()).isNull();
        assertThat(new SecurityEvent("sec-3", Instant.now(), Severity.HIGH, "d", "mcp:x", "f", "isolar", "isolar",
                "CONTAINED", "AUTO_CONTAINMENT", true, "msg-1").userMessageId()).isEqualTo("msg-1");
    }

    @Test
    void disjuntorSoFechaPorDecisaoDoUsuario() {
        CircuitBreakers breakers = new CircuitBreakers(Clock.systemUTC());

        assertThat(breakers.open("agent:developer", "tentou tocar a política", "fnd-1"))
                .isEqualTo(CircuitBreakers.State.CLOSED);
        assertThat(breakers.isOpen("agent:developer")).isTrue();
        assertThat(breakers.open("agent:developer", "de novo", "fnd-2")).isEqualTo(CircuitBreakers.State.OPEN);
        assertThat(breakers.release("agent:outro", "closed")).isEmpty();

        assertThat(breakers.release("agent:developer", "supervised")).contains(CircuitBreakers.State.HALF_OPEN);
        assertThat(breakers.isOpen("agent:developer")).isFalse();
        assertThat(breakers.isSupervised("agent:developer")).isTrue();
        assertThat(breakers.release("agent:developer", "closed")).contains(CircuitBreakers.State.CLOSED);
        assertThat(breakers.wire()).isEmpty();
        assertThat(breakers.release("agent:developer", "closed")).as("fechado não se libera").isEmpty();
    }

    private Path process(String pid, String command, Path... open) throws IOException {
        Path dir = home.resolve("proc/" + pid);
        Files.createDirectories(dir.resolve("fd"));
        Files.writeString(dir.resolve("comm"), command + "\n");
        int fd = 3;
        for (Path file : open) {
            Files.createSymbolicLink(dir.resolve("fd/" + fd++), file);
        }
        return dir;
    }

    @AcceptanceCriteria("SPEC-027/CA-4")
    @Test
    void credencialAbertaPorProcessoDesconhecidoViraObservacao() throws Exception {
        Path ssh = home.resolve(".ssh");
        Files.createDirectories(ssh);
        Path key = Files.writeString(ssh.resolve("id_rsa"), "chave");
        Path outro = Files.writeString(home.resolve("notas.txt"), "nada");
        process("100", "curl", key);
        process("200", "ssh", key);
        process("300", "bash", outro);
        List<Observation> seen = new CopyOnWriteArrayList<>();
        try (HostWatch watch = new HostWatch(home.resolve("proc"), home, Clock.systemUTC(), seen::add)) {

            List<Observation> found = watch.credentials();

            assertThat(found).singleElement().satisfies(observation -> {
                assertThat(observation.kind()).isEqualTo("host.credential-access");
                assertThat(observation.subject().toString()).isEqualTo("process:100");
                assertThat(observation.data()).containsEntry("processo", "curl");
                assertThat(String.valueOf(observation.data().get("arquivo"))).endsWith("/.ssh/id_rsa");
            });
            assertThat(seen).hasSize(1);
            assertThat(watch.credentials()).as("um aviso enquanto o descritor segue aberto").isEmpty();

            Files.delete(home.resolve("proc/100/fd/3"));
            assertThat(watch.credentials()).isEmpty();
            Files.createSymbolicLink(home.resolve("proc/100/fd/3"), key);
            assertThat(watch.credentials()).as("abriu de novo, avisa de novo").hasSize(1);
            assertThat(watch.status()).containsEntry("openAlerts", 1);
        }
    }

    @Test
    void portaNovaEmListenEArquivoDePersistenciaViramObservacao() throws Exception {
        Path proc = home.resolve("proc");
        Files.createDirectories(proc.resolve("net"));
        Files.writeString(proc.resolve("net/tcp"), """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid
                   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000
                   1: 0100007F:2233 0100007F:9999 01 00000000:00000000 00:00000000 00000000  1000
                """);
        Files.writeString(proc.resolve("net/tcp6"), "  sl  local_address\n");
        List<Observation> seen = new CopyOnWriteArrayList<>();
        try (HostWatch watch = new HostWatch(proc, home, Clock.systemUTC(), seen::add)) {

            assertThat(watch.newListeners()).as("a primeira varredura é a linha de base").isEmpty();

            Files.writeString(proc.resolve("net/tcp"), """
                      sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid
                       0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000
                       2: 00000000:22B8 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000
                    """);
            assertThat(watch.newListeners()).singleElement().satisfies(observation -> {
                assertThat(observation.kind()).isEqualTo("host.new-listener");
                assertThat(observation.data()).containsEntry("porta", "8888");
            });
            assertThat(watch.newListeners()).as("já conhecida").isEmpty();

            watch.persistenceChanged(home.resolve(".bashrc"));
            assertThat(seen.getLast().kind()).isEqualTo("host.persistence");
            assertThat(seen.getLast().reason()).contains(".bashrc");
        }
    }
}
