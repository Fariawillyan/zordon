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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import zordon.ai.cli.CliRunner;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;
import zordon.security.AuditLog;
import zordon.security.Gatekeeper;
import zordon.security.PermissionEngine;
import zordon.security.ProcessRunner;

/**
 * O CLI de um provider por assinatura, pelo caminho mediado (SPEC-018 CA-3): a
 * chamada é autorizada e auditada como {@code ai.cli}, o prompt vai pela entrada
 * padrão e não entra na auditoria, e o processo roda numa pasta vazia.
 */
@Spec("SPEC-018")
public final class GatekeptCliRunner implements CliRunner {

    private final Gatekeeper gatekeeper;
    private final ProcessRunner runner;
    private final Path workDir;
    private final java.util.function.Predicate<String> catalog;

    public GatekeptCliRunner(Gatekeeper gatekeeper, ProcessRunner runner, Path workDir,
            java.util.function.Predicate<String> catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.gatekeeper = Objects.requireNonNull(gatekeeper, "gatekeeper");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    @Override
    public boolean available(String program) {
        return catalog.test(program);
    }

    @Override
    public Result run(List<String> argv, String stdin, Duration timeout) throws Exception {
        Files.createDirectories(workDir);
        ActionDescriptor action = new ActionDescriptor("ai.cli",
                Map.of("program", argv.getFirst(), "promptChars", stdin == null ? 0 : stdin.length()),
                RiskLevel.GREEN, Set.of(Effect.SPAWN_PROCESS, Effect.NETWORK),
                List.of(ZPath.ofWsl(workDir.toAbsolutePath().toString())), 1, argv,
                "Perguntar ao modelo pela assinatura (" + argv.getFirst() + ")");
        Gatekeeper.Permit permit = gatekeeper.authorize(action, new Principal("system:ai", RequestOrigin.UI, false),
                PermissionEngine.PolicyContext.interactive(), null).get(PermissionEngine.APPROVAL_TTL.toSeconds() + 5,
                TimeUnit.SECONDS);
        if (!(permit instanceof Gatekeeper.Permit.Granted granted)) {
            throw new IllegalStateException(permit.decision().reason());
        }
        long started = System.nanoTime();
        try {
            ProcessRunner.Result result = runner.run(granted, workDir, timeout, stdin);
            gatekeeper.complete(granted, result.exitCode() == 0 ? AuditLog.Status.OK : AuditLog.Status.FAILED,
                    Duration.ofNanos(System.nanoTime() - started), result.stdout().length() + " bytes de resposta",
                    result.timedOut() ? "tempo esgotado" : result.exitCode() == 0 ? null : "código " + result.exitCode());
            return new Result(result.exitCode(), result.stdout(), result.stderr(), result.timedOut());
        } catch (Exception e) {
            gatekeeper.complete(granted, AuditLog.Status.FAILED, Duration.ofNanos(System.nanoTime() - started), null,
                    e.toString());
            throw e;
        }
    }
}
