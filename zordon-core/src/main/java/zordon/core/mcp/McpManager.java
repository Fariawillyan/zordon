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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.Severity;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;
import zordon.core.notify.NotificationCenter;
import zordon.core.tools.SkillRuntime;
import zordon.security.Gatekeeper;
import zordon.security.PermissionEngine;
import zordon.security.ProcessRunner;

/**
 * Os servidores MCP declarados, do início ao fim (SPEC-020). Conecta em segundo
 * plano: o núcleo não espera ninguém. Superfície que mudou não entra sem o
 * usuário aprovar na tela.
 */
@Spec("SPEC-020")
public final class McpManager implements AutoCloseable {

    /** Um servidor do {@code config.toml}. */
    public record Server(String name, List<String> command, RiskLevel floor, boolean autostart) {}

    public enum State { STOPPED, STARTING, CONNECTED, DRIFT, RECONNECTING, FAILED }

    static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(10);
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60),
            Duration.ofMinutes(5));
    static final Duration NEW_TOOL = Duration.ofDays(7);
    static final String PROTOCOL = "2025-06-18";

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final List<Server> servers;
    private final Gatekeeper gatekeeper;
    private final ProcessRunner runner;
    private final SkillRuntime tools;
    private final NotificationCenter notifications;
    private final Path stateDir;
    private final Path workDir;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile Duration callTimeout = McpTool.CALL_TIMEOUT;
    private volatile List<Duration> backoff = BACKOFF;
    private volatile java.util.function.Consumer<Map<String, Object>> alerts = alert -> { };

    private final class Connection {
        final Server server;
        volatile State state = State.STOPPED;
        volatile McpClient client;
        volatile String error;
        volatile List<Map<String, Object>> pendingSurface;
        volatile int attempts;
        /** Isolado pela defesa (SPEC-027): fica fora até o usuário liberar. */
        volatile boolean isolated;
        final McpTool.Breaker breaker;
        final List<String> registered = new ArrayList<>();

        Connection(Server server) {
            this.server = server;
            this.breaker = new McpTool.Breaker(nanos);
        }
    }

    /** Com quem o gerenciador fala para mediar uma chamada MCP. */
    public record Deps(Gatekeeper gatekeeper, ProcessRunner runner, SkillRuntime tools,
            NotificationCenter notifications) {}

    /** Onde o estado dos servidores e o trabalho das chamadas ficam em disco. */
    public record Dirs(Path stateDir, Path workDir) {}

    public McpManager(List<Server> servers, Deps deps, Dirs dirs, Clock clock, LongSupplier nanos) {
        this.servers = List.copyOf(servers);
        this.gatekeeper = Objects.requireNonNull(deps.gatekeeper(), "gatekeeper");
        this.runner = Objects.requireNonNull(deps.runner(), "runner");
        this.tools = Objects.requireNonNull(deps.tools(), "tools");
        this.notifications = Objects.requireNonNull(deps.notifications(), "notifications");
        this.stateDir = Objects.requireNonNull(dirs.stateDir(), "stateDir");
        this.workDir = Objects.requireNonNull(dirs.workDir(), "workDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        servers.forEach(server -> connections.put(server.name(), new Connection(server)));
    }

    /** {@code [[mcp.server]]} do {@code config.toml}. Entrada inválida é ignorada com aviso. */
    public static List<Server> load(Path configToml) {
        if (!Files.exists(configToml)) {
            return List.of();
        }
        try {
            TomlParseResult toml = Toml.parse(configToml);
            TomlArray array = toml.getArray("mcp.server");
            if (array == null) {
                return List.of();
            }
            List<Server> out = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                TomlTable table = array.getTable(i);
                String name = table.getString("name");
                TomlArray command = table.getArray("command");
                if (name == null || !name.matches("[a-z0-9-]{1,32}") || command == null || command.isEmpty()) {
                    log.warn("mcp.server #{} ignorado: precisa de name (a-z, 0-9, -) e command", i + 1);
                    continue;
                }
                List<String> argv = new ArrayList<>();
                for (int k = 0; k < command.size(); k++) {
                    argv.add(command.getString(k));
                }
                String floor = table.getString("risk_floor");
                Boolean autostart = table.getBoolean("autostart");
                out.add(new Server(name, List.copyOf(argv),
                        floor == null ? RiskLevel.YELLOW : RiskLevel.valueOf(floor.toUpperCase(java.util.Locale.ROOT)),
                        autostart == null || autostart));
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            log.warn("configuração de MCP ilegível: {}", e.getMessage());
            return List.of();
        }
    }

    /** {@code SYSTEM_ALERT} de conexão, queda e falha (SPEC-020 §8). */
    public McpManager onAlert(java.util.function.Consumer<Map<String, Object>> listener) {
        this.alerts = Objects.requireNonNull(listener, "listener");
        return this;
    }

    private void alert(Connection connection, String what, String detail) {
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

    /** Para os testes: prazos curtos no lugar de 30 s e de 5 s–5 min. */
    McpManager timings(Duration call, List<Duration> waits) {
        this.callTimeout = call;
        this.backoff = List.copyOf(waits);
        return this;
    }

    /** Conecta os servidores {@code autostart}, em segundo plano. */
    public void start() {
        connections.values().stream().filter(connection -> connection.server.autostart())
                .forEach(connection -> Thread.ofVirtual().name("mcp-connect-" + connection.server.name())
                        .start(() -> connect(connection)));
    }

    private void connect(Connection connection) {
        Server server = connection.server;
        if (!running) {
            return;
        }
        connection.state = State.STARTING;
        try {
            Files.createDirectories(workDir);
            ActionDescriptor action = new ActionDescriptor("mcp.start", Map.of("server", server.name()),
                    RiskLevel.GREEN, java.util.Set.of(Effect.SPAWN_PROCESS), List.of(ZPath.ofWsl(workDir.toString())), 1,
                    server.command(), "Iniciar o servidor MCP " + server.name());
            Gatekeeper.Permit permit = gatekeeper.authorize(action, new Principal("system:mcp", RequestOrigin.UI, false),
                    PermissionEngine.PolicyContext.interactive(), null).get(70, TimeUnit.SECONDS);
            if (!(permit instanceof Gatekeeper.Permit.Granted granted)) {
                fail(connection, "início negado: " + permit.decision().reason(), false);
                return;
            }
            zordon.security.LiveProcess live = runner.start(granted, workDir);
            granted.complete(new zordon.security.AuditLog.Completion(
                    zordon.security.AuditLog.Status.OK, Duration.ZERO, "processo iniciado", null));
            McpClient client = new McpClient(server.name(), live, () -> dropped(connection));
            connection.client = client;
            client.request("initialize", Map.of("protocolVersion", PROTOCOL, "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "zordon", "version", "0.1")), INITIALIZE_TIMEOUT)
                    .get(INITIALIZE_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            client.notify("notifications/initialized", Map.of());
            List<Map<String, Object>> surface = listTools(client);
            connection.attempts = 0;
            String hash = hash(surface);
            String approved = approvedSurfaces().get(server.name());
            if (approved != null && !approved.equals(hash)) {
                connection.pendingSurface = surface;
                log.warn("MCP {}: a superfície mudou; ferramentas suspensas até aprovação", server.name());
                notifications.publish(notifications.message(new NotificationCenter.MessageFields(Severity.HIGH, "AI_DEFENSE",
                        "O servidor MCP " + server.name() + " mudou o que oferece",
                        "As ferramentas declaradas por " + server.name() + " não são mais as que você aprovou.",
                        "Um servidor que muda de superfície depois de uma atualização pode passar a expor algo perigoso.",
                        "comparação da superfície do MCP na conexão (ai.mcp-drift)",
                        "As ferramentas desse servidor não foram oferecidas ao modelo.",
                        "servidor MCP " + server.name(), true,
                        "Servidor conectado, ferramentas suspensas.",
                        List.of("Revisar e aprovar a superfície nova na tela", "Manter suspenso"))));
                connection.state = State.DRIFT;
                return;
            }
            if (approved == null) {
                saveSurface(server.name(), hash);
            }
            register(connection, surface);
        } catch (Exception e) {
            fail(connection, e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                    ? e.getCause().getMessage() : e.getMessage(), true);
        }
    }

    private List<Map<String, Object>> listTools(McpClient client) throws Exception {
        List<Map<String, Object>> surface = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            Map<String, Object> page = client.request("tools/list", params, INITIALIZE_TIMEOUT)
                    .get(INITIALIZE_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
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

    private synchronized void register(Connection connection, List<Map<String, Object>> surface) throws IOException {
        Map<String, String> seen = firstSeen();
        Instant now = clock.instant();
        for (Map<String, Object> declared : surface) {
            String key = connection.server.name() + "." + declared.get("name");
            Instant first = seen.containsKey(key) ? Instant.parse(seen.get(key)) : now;
            seen.putIfAbsent(key, now.toString());
            boolean fresh = Duration.between(first, now).compareTo(NEW_TOOL) < 0;
            McpTool tool = new McpTool(connection.server.name(), declared, connection.server.floor(), fresh,
                    new McpTool.Wiring(() -> connection.client, connection.breaker, callTimeout));
            tools.register(tool);
            connection.registered.add(tool.name());
        }
        write(stateDir.resolve("mcp-seen.json"), seen);
        connection.state = State.CONNECTED;
        connection.error = null;
        log.info("MCP {}: {} ferramentas registradas", connection.server.name(), connection.registered.size());
        alert(connection, "connected", connection.registered.size() + " ferramentas");
    }

    /** Aprovação pela tela de uma superfície nova (SPEC-020 CA-3). */
    public synchronized boolean approve(String name) throws IOException {
        Connection connection = connections.get(name);
        if (connection == null || connection.state != State.DRIFT || connection.pendingSurface == null) {
            return false;
        }
        saveSurface(name, hash(connection.pendingSurface));
        register(connection, connection.pendingSurface);
        connection.pendingSurface = null;
        return true;
    }

    /** Desconecta e tira as ferramentas, sem matar o processo do servidor (SPEC-027). */
    public boolean isolate(String name) {
        Connection connection = connections.get(name);
        if (connection == null) {
            return false;
        }
        connection.isolated = true;
        connection.state = State.FAILED;
        connection.error = "isolado pela defesa";
        McpClient client = connection.client;
        if (client != null) {
            client.close();
        }
        synchronized (this) {
            connection.registered.forEach(tools::unregister);
            connection.registered.clear();
        }
        log.warn("MCP {}: isolado pela defesa", name);
        return true;
    }

    /** O usuário liberou: volta a conectar. */
    public boolean release(String name) {
        Connection connection = connections.get(name);
        if (connection == null || !connection.isolated) {
            return false;
        }
        connection.isolated = false;
        connection.attempts = 0;
        Thread.ofVirtual().name("mcp-reconnect-" + name).start(() -> connect(connection));
        return true;
    }

    private void dropped(Connection connection) {
        synchronized (this) {
            connection.registered.forEach(tools::unregister);
            connection.registered.clear();
        }
        if (!running || connection.isolated || connection.state == State.FAILED) {
            return;
        }
        connection.state = State.RECONNECTING;
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

    private void fail(Connection connection, String reason, boolean retry) {
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
        connection.state = State.FAILED;
        alert(connection, "failed", reason);
    }

    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Server server : servers) {
            Connection connection = connections.get(server.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", server.name());
            row.put("state", connection.state.name().toLowerCase(java.util.Locale.ROOT));
            row.put("tools", List.copyOf(connection.registered));
            row.put("drift", connection.state == State.DRIFT);
            if (connection.error != null) {
                row.put("error", connection.error);
            }
            out.add(row);
        }
        return out;
    }

    public State state(String name) {
        Connection connection = connections.get(name);
        return connection == null ? State.STOPPED : connection.state;
    }

    static String hash(List<Map<String, Object>> surface) throws IOException {
        List<Map<String, Object>> sorted = new ArrayList<>(surface);
        sorted.sort(java.util.Comparator.comparing(tool -> String.valueOf(tool.get("name"))));
        List<Map<String, Object>> relevant = sorted.stream().map(tool -> {
            Map<String, Object> row = new java.util.TreeMap<>();
            row.put("name", tool.get("name"));
            row.put("description", tool.get("description"));
            row.put("inputSchema", tool.get("inputSchema"));
            row.put("annotations", tool.get("annotations"));
            return (Map<String, Object>) row;
        }).toList();
        try {
            byte[] canonical = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(relevant);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> approvedSurfaces() {
        return read(stateDir.resolve("mcp-surface.json"));
    }

    private synchronized void saveSurface(String name, String hash) throws IOException {
        Map<String, String> surfaces = approvedSurfaces();
        surfaces.put(name, hash);
        write(stateDir.resolve("mcp-surface.json"), surfaces);
    }

    private Map<String, String> firstSeen() {
        return read(stateDir.resolve("mcp-seen.json"));
    }

    private static Map<String, String> read(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(json.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, String>>() { }));
        } catch (IOException e) {
            log.warn("estado de MCP ilegível em {}: {}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static void write(Path file, Map<String, String> content) throws IOException {
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, json.writeValueAsString(content), StandardCharsets.UTF_8);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void close() {
        running = false;
        connections.values().forEach(connection -> {
            McpClient client = connection.client;
            if (client != null) {
                client.close();
            }
        });
    }
}
