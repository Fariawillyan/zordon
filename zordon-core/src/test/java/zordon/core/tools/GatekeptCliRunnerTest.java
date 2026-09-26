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
package zordon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.cli.ClaudeCliProvider;
import zordon.api.trace.AcceptanceCriteria;
import zordon.security.CommandValidator;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngines;
import zordon.security.ProcessRunner;
import zordon.security.Redactor;
import zordon.security.SqliteAuditLog;

/** O claude CLI pelo caminho mediado, com um "claude" falso (SPEC-018). */
class GatekeptCliRunnerTest {

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-018/CA-3")
    @Test
    void chamadaPassaPeloGatekeeperEAuditoriaNaoGuardaOPrompt() throws Exception {
        // O "claude" falso: guarda a pasta onde rodou, o ambiente e o que leu da entrada padrão.
        Path fake = home.resolve("bin/claude");
        Files.createDirectories(fake.getParent());
        // Caminhos absolutos da pasta do teste: o HOME do filho é o de verdade, e o teste não escreve lá.
        Path out = home.resolve("saida");
        Files.createDirectories(out);
        Files.writeString(fake, """
                #!/bin/sh
                pwd > "%1$s/onde"
                env > "%1$s/ambiente"
                cat > "%1$s/entrada"
                printf '{"type":"result","subtype":"success","is_error":false,"result":"Oi!","usage":{"input_tokens":3,"output_tokens":2}}'
                """.formatted(out));
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        CommandValidator validator = new CommandValidator(Map.of("claude", fake.toString()));
        try (SqliteAuditLog audit = new SqliteAuditLog(home.resolve("audit.db"), new Redactor(), Clock.systemUTC())) {
            Gatekeeper gatekeeper = new Gatekeeper(PermissionEngines.standard(
                    PathPolicy.defaults(home.toString(), List.of(), List.of("~")), validator, new Redactor(), () -> null),
                    audit);
            Path work = home.resolve("cli-work");
            GatekeptCliRunner runner = new GatekeptCliRunner(gatekeeper, new ProcessRunner(validator), work,
                    program -> program.equals("claude"));
            assertThat(runner.available("claude")).isTrue();
            assertThat(runner.available("codex")).isFalse();

            String answer = new ClaudeCliProvider("claude", runner).chat(AiRequest.builder("sonnet")
                    .systemPrompt("Você é o Zordon.").messages(List.of(AiMessage.user("minha senha é hunter22-xyz")))
                    .maxOutputTokens(100).timeout(Duration.ofSeconds(30)).build()).text();

            assertThat(answer).isEqualTo("Oi!");
            assertThat(Files.readString(out.resolve("entrada"))).isEqualTo("minha senha é hunter22-xyz");
            assertThat(Files.readString(out.resolve("onde")).strip()).isEqualTo(work.toString());
            assertThat(Files.readAllLines(out.resolve("ambiente"))).allMatch(line -> line.matches(
                    "^(PATH|HOME|LANG|LC_ALL|USER|TERM|PWD|SHLVL|_|OLDPWD)=.*"));
            assertThat(audit.entries()).isEqualTo(2);
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("audit.db"));
                    var s = db.createStatement();
                    ResultSet r = s.executeQuery("SELECT tool, args_json, status FROM audit ORDER BY id")) {
                r.next();
                assertThat(r.getString(1)).isEqualTo("ai.cli");
                assertThat(r.getString(2)).contains("promptChars").doesNotContain("hunter22");
                r.next();
                assertThat(r.getString(3)).isEqualTo("ok");
            }
        }
    }
}
