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

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.mcp.McpManager;

/** {@code mcp.servers} e {@code mcp.approve} (SPEC-020). */
@Spec("SPEC-020")
public final class McpMethods {

    private final McpManager mcp;

    public McpMethods(McpManager mcp) {
        this.mcp = Objects.requireNonNull(mcp, "mcp");
    }

    public void registerOn(ZwpServer server) {
        server.register("mcp.servers", (session, params) -> Map.of("servers", mcp.describe()))
                .register("mcp.approve", (session, params) -> {
                    // Aceitar uma superfície nova é decisão na tela, como sair do lockdown (SPEC-020 CA-3).
                    if (!session.is(ClientKind.DESKTOP)) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED,
                                "só a janela do Zordon aprova a superfície de um servidor MCP");
                    }
                    if (!(params.get("server") instanceof String name) || name.isBlank()) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "server é obrigatório");
                    }
                    try {
                        return Map.of("approved", mcp.approve(name));
                    } catch (IOException e) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_MCP_UNAVAILABLE, "aprovação não gravada: "
                                + e.getMessage());
                    }
                });
    }
}
