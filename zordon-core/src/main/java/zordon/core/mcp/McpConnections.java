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

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Os servidores declarados e suas conexões, pelo nome: iniciar, aprovar, isolar, liberar e descrever. */
final class McpConnections {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private final List<McpManager.Server> servers;
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final McpSurfaces surfaces;
    private final McpToolset toolset;
    private final McpLifecycle lifecycle;
    private final Object lock;

    McpConnections(List<McpManager.Server> servers, McpManager.Deps deps, McpManager.Dirs dirs, Clock clock,
            LongSupplier nanos, Object lock) {
        this.servers = List.copyOf(servers);
        this.lock = lock;
        this.surfaces = new McpSurfaces(Objects.requireNonNull(dirs.stateDir(), "stateDir"),
                Objects.requireNonNull(deps.notifications(), "notifications"), lock);
        this.toolset = new McpToolset(Objects.requireNonNull(deps.tools(), "tools"), surfaces,
                Objects.requireNonNull(clock, "clock"));
        this.lifecycle = new McpLifecycle(new McpLauncher(Objects.requireNonNull(deps.gatekeeper(), "gatekeeper"),
                Objects.requireNonNull(deps.runner(), "runner"), Objects.requireNonNull(dirs.workDir(), "workDir")),
                surfaces, toolset, lock);
        Objects.requireNonNull(nanos, "nanos");
        servers.forEach(server -> connections.put(server.name(), new McpConnection(server, nanos)));
    }

    McpLifecycle lifecycle() {
        return lifecycle;
    }

    void timings(Duration call, List<Duration> waits) {
        toolset.callTimeout(call);
        lifecycle.backoff(waits);
    }

    /** Conecta os servidores {@code autostart}, em segundo plano. */
    void start() {
        connections.values().stream().filter(connection -> connection.server.autostart())
                .forEach(connection -> Thread.ofVirtual().name("mcp-connect-" + connection.server.name())
                        .start(() -> lifecycle.connect(connection)));
    }

    /** Aprovação pela tela de uma superfície nova (SPEC-020 CA-3). Sob o lock. */
    boolean approve(String name) throws IOException {
        McpConnection connection = connections.get(name);
        if (connection == null || connection.state != McpManager.State.DRIFT || connection.pendingSurface == null) {
            return false;
        }
        surfaces.save(name, McpSurfaces.hash(connection.pendingSurface));
        lifecycle.register(connection, connection.pendingSurface);
        connection.pendingSurface = null;
        return true;
    }

    /** Desconecta e tira as ferramentas, sem matar o processo do servidor (SPEC-027). */
    boolean isolate(String name) {
        McpConnection connection = connections.get(name);
        if (connection == null) {
            return false;
        }
        connection.isolated = true;
        connection.state = McpManager.State.FAILED;
        connection.error = "isolado pela defesa";
        connection.closeClient();
        synchronized (lock) {
            toolset.remove(connection);
        }
        log.warn("MCP {}: isolado pela defesa", name);
        return true;
    }

    /** O usuário liberou: volta a conectar. */
    boolean release(String name) {
        McpConnection connection = connections.get(name);
        if (connection == null || !connection.isolated) {
            return false;
        }
        connection.isolated = false;
        connection.attempts = 0;
        Thread.ofVirtual().name("mcp-reconnect-" + name).start(() -> lifecycle.connect(connection));
        return true;
    }

    List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpManager.Server server : servers) {
            McpConnection connection = connections.get(server.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", server.name());
            row.put("state", connection.state.name().toLowerCase(Locale.ROOT));
            row.put("tools", List.copyOf(connection.registered));
            row.put("drift", connection.state == McpManager.State.DRIFT);
            if (connection.error != null) {
                row.put("error", connection.error);
            }
            out.add(row);
        }
        return out;
    }

    McpManager.State state(String name) {
        McpConnection connection = connections.get(name);
        return connection == null ? McpManager.State.STOPPED : connection.state;
    }

    void close() {
        lifecycle.stop();
        connections.values().forEach(McpConnection::closeClient);
    }
}
