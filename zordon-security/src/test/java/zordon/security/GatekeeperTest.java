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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.AcceptanceCriteria;

/** O caminho único até a execução (SPEC-016). */
class GatekeeperTest {

    @TempDir
    Path dir;

    private static final Map<String, String> CATALOG = Map.of("echo", "/bin/echo", "sleep", "/bin/sleep",
            "env", "/usr/bin/env", "head", "/usr/bin/head");

    private SqliteAuditLog audit;

    private Gatekeeper gatekeeper() {
        audit = new SqliteAuditLog(dir.resolve("audit.db"), new Redactor(), Clock.systemUTC());
        CommandValidator validator = new CommandValidator(CATALOG);
        return new Gatekeeper(PermissionEngines.standard(
                PathPolicy.defaults(dir.toString(), List.of("~"), List.of("~")), validator, new Redactor(),
                () -> null), audit);
    }

    private ActionDescriptor command(String... argv) {
        return new ActionDescriptor("process.run", Map.of("argv", List.of(argv)), RiskLevel.GREEN,
                Set.of(Effect.SPAWN_PROCESS), List.of(ZPath.ofWsl(dir.toString())), 1, List.of(argv),
                "Rodar " + String.join(" ", argv));
    }

    private Gatekeeper.Permit.Granted granted(Gatekeeper gatekeeper, String... argv) throws Exception {
        Gatekeeper.Permit permit = gatekeeper.authorize(command(argv), Principal.user(RequestOrigin.UI),
                PermissionEngine.PolicyContext.interactive(), "t1").get(5, TimeUnit.SECONDS);
        assertThat(permit).isInstanceOf(Gatekeeper.Permit.Granted.class);
        return (Gatekeeper.Permit.Granted) permit;
    }

    @AcceptanceCriteria("SPEC-016/CA-1")
    @Test
    void semAutorizacaoNadaRodaEAAuditoriaTemIntencaoEDesfecho() throws Exception {
        Gatekeeper gatekeeper = gatekeeper();
        try (SqliteAuditLog log = audit) {
            Gatekeeper.Permit.Granted permit = granted(gatekeeper, "echo", "olá");
            ProcessRunner.Result result = new ProcessRunner(new CommandValidator(CATALOG)).run(permit, dir,
                    Duration.ofSeconds(5));
            permit.complete(new AuditLog.Completion(AuditLog.Status.OK, Duration.ofMillis(3),
                    "saída de 1 linha", null));
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).isEqualTo("olá\n");

            // Um comando fora do catálogo é RED: sem tela para autorizar, é recusado e não roda.
            Gatekeeper.Permit refused = gatekeeper.authorize(command("/tmp/qualquer"), Principal.user(RequestOrigin.UI),
                    PermissionEngine.PolicyContext.interactive(), "t2").get(5, TimeUnit.SECONDS);
            assertThat(refused).isInstanceOf(Gatekeeper.Permit.Refused.class);
            assertThat(audit.entries()).as("intenção + desfecho, duas vezes").isEqualTo(4);
            assertThat(audit.verify(100).ok()).isTrue();
        }
    }

    @AcceptanceCriteria("SPEC-016/CA-2")
    @Test
    void semShellAmbienteSemSegredosPrazoESaidaLimitada() throws Exception {
        Gatekeeper gatekeeper = gatekeeper();
        ProcessRunner runner = new ProcessRunner(new CommandValidator(CATALOG));
        try (SqliteAuditLog log = audit) {
            Gatekeeper.Permit shell = gatekeeper.authorize(command("bash", "-c", "id"), Principal.user(RequestOrigin.UI),
                    PermissionEngine.PolicyContext.interactive(), "t").get(5, TimeUnit.SECONDS);
            assertThat(shell).isInstanceOf(Gatekeeper.Permit.Refused.class);

            ProcessRunner.Result env = runner.run(granted(gatekeeper, "env"), dir, Duration.ofSeconds(5));
            List<String> keys = Arrays.stream(env.stdout().split("\n")).filter(line -> line.contains("="))
                    .map(line -> line.substring(0, line.indexOf('='))).toList();
            assertThat(keys).isNotEmpty().allMatch(key -> List.of("PATH", "HOME", "LANG", "LC_ALL", "USER", "TERM")
                    .contains(key));

            long started = System.nanoTime();
            ProcessRunner.Result slow = runner.run(granted(gatekeeper, "sleep", "10"), dir, Duration.ofMillis(300));
            assertThat(slow.timedOut()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

            ProcessRunner.Result big = runner.run(granted(gatekeeper, "head", "-c", "200000", "/dev/zero"), dir,
                    Duration.ofSeconds(5));
            assertThat(big.truncated()).isTrue();
            assertThat(big.stdout().length()).isLessThanOrEqualTo(ProcessRunner.MAX_OUTPUT);
        }
    }
}
