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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.security.LiveProcess;

/** A vida de cada conexão: conectar em segundo plano, cair, esperar e tentar de novo, avisando a cada passo. */
final class McpLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private final McpLauncher launcher;
    private final McpSurfaces surfaces;
    private final McpToolset toolset;
    private final Object lock;
    private volatile boolean running = true;
    private volatile List<Duration> backoff = McpManager.BACKOFF;
    private volatile Consumer<Map<String, Object>> alerts = alert -> { };

    McpLifecycle(McpLauncher launcher, McpSurfaces surfaces, McpToolset toolset, Object lock) {
        this.launcher = launcher;
        this.surfaces = surfaces;
        this.toolset = toolset;
        this.lock = lock;
    }

    /** {@code SYSTEM_ALERT} de conexão, queda e falha (SPEC-020 §8). */
    void onAlert(Consumer<Map<String, Object>> listener) {
        this.alerts = Objects.requireNonNull(listener, "listener");
    }

    void backoff(List<Duration> waits) {
        this.backoff = List.copyOf(waits);
    }

    void stop() {
        running = false;
    }

    void connect(McpConnection connection) {
        McpManager.Server server = connection.server;
        if (!running) {
            return;
        }
        connection.state = McpManager.State.STARTING;
        try {
            LiveProcess live = launcher.launch(server);
            McpClient client = new McpClient(server.name(), live, () -> dropped(connection));
            connection.client = client;
            client.request("initialize", Map.of("protocolVersion", McpManager.PROTOCOL, "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "zordon", "version", "0.1")), McpManager.INITIALIZE_TIMEOUT)
                    .get(McpManager.INITIALIZE_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            client.notify("notifications/initialized", Map.of());
            List<Map<String, Object>> surface = listTools(client);
            connection.attempts = 0;
            if (surfaces.drifted(server, surface)) {
                connection.pendingSurface = surface;
                connection.state = McpManager.State.DRIFT;
                return;
            }
            register(connection, surface);
        } catch (McpLauncher.Denied e) {
            fail(connection, e.getMessage(), false);
        } catch (Exception e) {
            fail(connection, e instanceof ExecutionException && e.getCause() != null
                    ? e.getCause().getMessage() : e.getMessage(), true);
        }
    }

    private List<Map<String, Object>> listTools(McpClient client) throws Exception {
        List<Map<String, Object>> surface = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            Map<String, Object> page = client.request("tools/list", params, McpManager.INITIALIZE_TIMEOUT)
                    .get(McpManager.INITIALIZE_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            if (page.get("tools") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> tool && tool.get("name") instanceof String) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) tool;
                        surface.add(typed);
                    }
                }
            }
            cursor = page.get("nextCursor") instanceof String next && !next.isBlank() ? next : null;
        } while (cursor != null && surface.size() < 500);
        return surface;
    }

    /** Aceita a superfície: as ferramentas entram e o servidor fica conectado. */
    void register(McpConnection connection, List<Map<String, Object>> surface) throws IOException {
        synchronized (lock) {
            toolset.add(connection, surface);
            connection.state = McpManager.State.CONNECTED;
            connection.error = null;
            log.info("MCP {}: {} ferramentas registradas", connection.server.name(), connection.registered.size());
            alert(connection, "connected", connection.registered.size() + " ferramentas");
        }
    }

    void dropped(McpConnection connection) {
        synchronized (lock) {
            toolset.remove(connection);
        }
        if (!running || connection.isolated || connection.state == McpManager.State.FAILED) {
            return;
        }
        connection.state = McpManager.State.RECONNECTING;
        List<Duration> waits = backoff;
        Duration wait = waits.get(Math.min(connection.attempts, waits.size() - 1));
        connection.attempts++;
        log.warn("MCP {}: caiu; nova tentativa em {} s", connection.server.name(), wait.toSeconds());
        alert(connection, "disconnected", "nova tentativa em " + wait.toSeconds() + " s");
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(wait);
                connect(connection);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void fail(McpConnection connection, String reason, boolean retry) {
        connection.error = reason;
        log.warn("MCP {}: {}", connection.server.name(), reason);
        McpClient client = connection.client;
        if (retry && client != null) {
            client.close();   // o leitor vê o fim e agenda a próxima tentativa
            return;
        }
        if (retry) {
            dropped(connection);
            return;
        }
        connection.state = McpManager.State.FAILED;
        alert(connection, "failed", reason);
    }

    private void alert(McpConnection connection, String what, String detail) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("source", "mcp");
        alert.put("server", connection.server.name());
        alert.put("event", what);
        if (detail != null) {
            alert.put("detail", detail);
        }
        try {
            alerts.accept(alert);
        } catch (RuntimeException e) {
            log.debug("alerta de MCP não entregue: {}", e.getMessage());
        }
    }
}
