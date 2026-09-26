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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Um servidor MCP e a conexão com ele: estado, cliente, superfície pendente e as ferramentas registradas. */
final class McpConnection {

    final McpManager.Server server;
    volatile McpManager.State state = McpManager.State.STOPPED;
    volatile McpClient client;
    volatile String error;
    volatile List<Map<String, Object>> pendingSurface;
    volatile int attempts;
    /** Isolado pela defesa (SPEC-027): fica fora até o usuário liberar. */
    volatile boolean isolated;
    final McpTool.Breaker breaker;
    final List<String> registered = new ArrayList<>();

    McpConnection(McpManager.Server server, LongSupplier nanos) {
        this.server = server;
        this.breaker = new McpTool.Breaker(nanos);
    }

    /** Fecha o cliente, se houver: o leitor vê o fim e o ciclo de vida decide o que vem depois. */
    void closeClient() {
        McpClient current = client;
        if (current != null) {
            current.close();
        }
    }
}
