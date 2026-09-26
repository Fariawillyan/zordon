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
package zordon.core.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.security.AuditLog;
import zordon.security.Gatekeeper;
import zordon.security.LiveProcess;
import zordon.security.PermissionEngine;
import zordon.security.ProcessRunner;

/** Inicia o processo de um servidor pelo caminho mediado (SPEC-016): autorizado, auditado e sem shell. */
final class McpLauncher {

    /** O Gatekeeper negou o início: tentar de novo daria o mesmo. */
    static final class Denied extends Exception {
        Denied(String reason) {
            super(reason);
        }
    }

    private final Gatekeeper gatekeeper;
    private final ProcessRunner runner;
    private final Path workDir;

    McpLauncher(Gatekeeper gatekeeper, ProcessRunner runner, Path workDir) {
        this.gatekeeper = gatekeeper;
        this.runner = runner;
        this.workDir = workDir;
    }

    LiveProcess launch(McpManager.Server server) throws Exception {
        Files.createDirectories(workDir);
        ActionDescriptor action = new ActionDescriptor("mcp.start", Map.of("server", server.name()),
                RiskLevel.GREEN, Set.of(Effect.SPAWN_PROCESS), List.of(ZPath.ofWsl(workDir.toString())), 1,
                server.command(), "Iniciar o servidor MCP " + server.name());
        Gatekeeper.Permit permit = gatekeeper.authorize(action, new Principal("system:mcp", RequestOrigin.UI, false),
                PermissionEngine.PolicyContext.interactive(), null).get(70, TimeUnit.SECONDS);
        if (!(permit instanceof Gatekeeper.Permit.Granted granted)) {
            throw new Denied("início negado: " + permit.decision().reason());
        }
        LiveProcess live = runner.start(granted, workDir);
        granted.complete(new AuditLog.Completion(AuditLog.Status.OK, Duration.ZERO, "processo iniciado", null));
        return live;
    }
}
