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

import java.util.Map;
import java.util.Objects;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.core.tools.SkillRuntime;
import zordon.core.tools.WindowsBridge;

/** {@code tools.list} e o registro dos hosts que abrem aplicativos (SPEC-016). */
@Spec("SPEC-016")
public final class ToolMethods {

    private final SkillRuntime tools;
    private final WindowsBridge windows;
    private final zordon.security.vault.QuarantineVault vault;

    public ToolMethods(SkillRuntime tools, WindowsBridge windows, zordon.security.vault.QuarantineVault vault) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    public void registerOn(ZwpServer server) {
        server.register("tools.list", (session, params) -> Map.of("tools", tools.list()))
                .register("security.quarantine.list", (session, params) -> Map.of("items",
                        vault.list().stream().map(item -> Map.<String, Object>of("vaultId", item.vaultId(),
                                "ts", item.ts(), "reason", item.reason(), "root", item.root(), "files", item.files(),
                                "bytes", item.bytes(), "restored", item.restored())).toList()))
                // Restaurar é uma ferramenta como outra: passa pelo Gatekeeper, com a origem de quem pediu (SPEC-017).
                .registerAsync("security.quarantine.restore", (session, params) -> tools.invoke("fs.restore",
                                Map.of("vaultId", String.valueOf(params.get("vaultId"))),
                                zordon.api.security.Principal.user(session.is(ClientKind.DESKTOP)
                                        ? zordon.api.security.RequestOrigin.UI
                                        : zordon.api.security.RequestOrigin.AUTOMATION),
                                "restore-" + params.get("vaultId"))
                        .thenApply(result -> Map.<String, Object>of("text", result.text())))
                .onSessionReady(session -> {
                    if (session.is(ClientKind.HOST) && session.capabilities().contains(WindowsBridge.CAPABILITY)) {
                        windows.hostConnected(session.id());
                    }
                })
                .onSessionClosed(session -> windows.hostDisconnected(session.id()));
    }
}
