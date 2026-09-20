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
package zordon.security.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;

/** O cofre de quarentena: só move, e devolve (SPEC-017). */
class QuarantineVaultTest {

    @TempDir
    Path dir;

    private Path logs() throws Exception {
        Path logs = Files.createDirectories(dir.resolve("projeto/logs/antigos"));
        Files.writeString(dir.resolve("projeto/logs/a.log"), "linha a");
        Files.writeString(dir.resolve("projeto/logs/b.log"), "linha b");
        Files.writeString(logs.resolve("c.log"), "linha c");
        return dir.resolve("projeto/logs");
    }

    @AcceptanceCriteria("SPEC-017/CA-1")
    @Test
    void guardaMoveComManifestoENadaEApagado() throws Exception {
        Path logs = logs();
        QuarantineVault vault = new QuarantineVault(dir.resolve("cofre"), Clock.systemUTC());
        List<Path> files = QuarantineVault.filesUnder(logs);
        assertThat(files).hasSize(3);

        QuarantineVault.Item item = vault.store(files, logs, "limpar os logs");

        assertThat(item.files()).isEqualTo(3);
        assertThat(item.bytes()).isEqualTo(21);
        assertThat(item.notes()).isEmpty();
        assertThat(files).allSatisfy(file -> assertThat(file).doesNotExist());
        assertThat(logs.resolve("antigos")).as("pastas ficam; só arquivos saem").isDirectory();
        Path manifest = dir.resolve("cofre").resolve(item.vaultId()).resolve("manifest.json");
        String text = Files.readString(manifest);
        assertThat(text).contains("a.log", "sha256", "\"state\" : \"stored\"", "limpar os logs");
        try (var stored = Files.list(dir.resolve("cofre").resolve(item.vaultId()).resolve("files"))) {
            assertThat(stored.count()).isEqualTo(3);
        }
        assertThat(vault.list()).singleElement().satisfies(listed -> {
            assertThat(listed.vaultId()).isEqualTo(item.vaultId());
            assertThat(listed.restored()).isFalse();
        });
    }

    @AcceptanceCriteria("SPEC-017/CA-2")
    @Test
    void restaurarDevolveComOMesmoConteudoENaoSobrescreve() throws Exception {
        Path logs = logs();
        QuarantineVault vault = new QuarantineVault(dir.resolve("cofre"), Clock.systemUTC());
        QuarantineVault.Item item = vault.store(QuarantineVault.filesUnder(logs), logs, "teste");
        // Enquanto estava no cofre, alguém recriou b.log: ele não pode ser sobrescrito.
        Files.writeString(logs.resolve("b.log"), "novo conteúdo");

        QuarantineVault.Item restored = vault.restore(item.vaultId());

        assertThat(Files.readString(logs.resolve("a.log"))).isEqualTo("linha a");
        assertThat(Files.readString(logs.resolve("antigos/c.log"))).isEqualTo("linha c");
        assertThat(Files.readString(logs.resolve("b.log"))).as("não sobrescreve").isEqualTo("novo conteúdo");
        assertThat(restored.restored()).isFalse();
        assertThat(restored.notes()).singleElement().asString().contains("b.log").contains("occupied");
        assertThat(Map.of("vault", vault.list().getFirst().files())).containsEntry("vault", 3);
    }
}
