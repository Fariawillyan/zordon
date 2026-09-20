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
package zordon.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.AcceptanceCriteria;

/** Caminhos canônicos e comandos sem shell (SPEC-014). */
class CommandAndPathTest {

    @TempDir
    Path home;

    private PathPolicy policy() {
        return PathPolicy.defaults(home.toString(), List.of("~/dev", "D:/projetos"), List.of("~", "D:/"));
    }

    @AcceptanceCriteria("SPEC-014/CA-4")
    @Test
    void caminhoRelativoComPontosOuPorSymlinkEhClassificadoPeloDestino() throws Exception {
        Path dev = Files.createDirectories(home.resolve("dev/app"));
        Files.createDirectories(home.resolve(".ssh"));
        Files.createSymbolicLink(dev.resolve("chaves"), home.resolve(".ssh"));
        PathPolicy policy = policy();
        ZPath base = ZPath.ofWsl(dev.toString());

        ZPath dotted = policy.canonical("../../.ssh/id_ed25519", base);
        assertThat(dotted.canonical()).isEqualTo(home.resolve(".ssh/id_ed25519").toString());
        assertThat(policy.classify(dotted).forbidden()).isTrue();

        ZPath linked = policy.canonical("chaves/id_ed25519", base);
        assertThat(linked.canonical()).isEqualTo(home.resolve(".ssh/id_ed25519").toString());
        assertThat(policy.classify(linked).forbidden()).isTrue();

        ZPath inside = policy.canonical("src/Main.java", base);
        assertThat(policy.classify(inside)).satisfies(v -> {
            assertThat(v.workspace()).isTrue();
            assertThat(v.forbidden()).isFalse();
        });
        assertThat(policy.classify(ZPath.ofWindows("C:\\Windows\\System32\\drivers\\etc\\hosts")).forbidden()).isTrue();
        assertThat(policy.classify(ZPath.ofWindows("d:\\PROJETOS\\site\\.env")).forbidden()).isTrue();
        assertThat(policy.classify(ZPath.ofWindows("D:\\projetos\\site\\.git")).forbidden()).isTrue();
        assertThat(policy.classify(ZPath.ofWindows("D:\\Projetos\\site\\index.html")).workspace()).isTrue();
        assertThat(policy.classify(ZPath.ofWindows("C:\\Users\\u\\AppData\\Roaming\\x")).critical()).isTrue();
        assertThat(policy.classify(ZPath.ofWsl(home + "/.local/share/zordon/lib/core.jar")).install()).isTrue();
    }

    @AcceptanceCriteria("SPEC-014/CA-5")
    @Test
    void semShellCatalogoESubcomandosPerigosos() {
        CommandValidator validator = new CommandValidator(Map.of("git", "/usr/bin/git", "docker", "/usr/bin/docker",
                "gradle", "/opt/gradle/bin/gradle"));

        assertThat(validator.validate(List.of("sh", "-c", "rm -rf /")))
                .isInstanceOf(CommandValidator.Validation.Denied.class);
        assertThat(validator.validate(List.of("C:\\Windows\\System32\\cmd.exe", "/c", "del")))
                .isInstanceOf(CommandValidator.Validation.Denied.class);
        assertThat(validator.validate(List.of("powershell", "-Command", "Get-Process")))
                .isInstanceOf(CommandValidator.Validation.Denied.class);

        assertThat(validator.validate(List.of("git", "status"))).isEqualTo(
                new CommandValidator.Validation.Accepted(RiskLevel.GREEN, "/usr/bin/git", null));
        assertThat(validator.validate(List.of("git", "reset", "--hard", "HEAD~3")))
                .extracting(v -> ((CommandValidator.Validation.Accepted) v).floor()).isEqualTo(RiskLevel.RED);
        assertThat(validator.validate(List.of("git", "push", "origin", "main", "--force-with-lease")))
                .extracting(v -> ((CommandValidator.Validation.Accepted) v).floor()).isEqualTo(RiskLevel.RED);
        assertThat(validator.validate(List.of("docker", "system", "prune", "-a")))
                .extracting(v -> ((CommandValidator.Validation.Accepted) v).floor()).isEqualTo(RiskLevel.RED);
        assertThat(validator.validate(List.of("/tmp/baixado/instalador", "--sim")))
                .isEqualTo(new CommandValidator.Validation.Accepted(RiskLevel.RED, "/tmp/baixado/instalador",
                        "programa fora do catálogo: /tmp/baixado/instalador"));
    }

    @Test
    void redatorMascaraComPrefixoETresUltimos() {
        Redactor redactor = new Redactor();
        String key = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789XYZ92F";
        assertThat(redactor.redact("chave " + key)).isEqualTo("chave sk-proj-" + "*".repeat(key.length() - 11) + "92F");
        assertThat(redactor.redact("password=hunter22 ok")).startsWith("password=").doesNotContain("hunter22");
        assertThat(redactor.redact("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"))
                .isEqualTo("[chave privada omitida]");
        assertThat(redactor.containsSecret("nada aqui")).isFalse();
    }
}
