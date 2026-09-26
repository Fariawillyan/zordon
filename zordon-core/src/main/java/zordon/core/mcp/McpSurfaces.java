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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.Severity;
import zordon.core.notify.NotificationCenter;

/**
 * O que cada servidor oferece, como o usuário aprovou (SPEC-020 CA-3), e quando
 * cada ferramenta apareceu. Superfície que mudou não entra sem aprovação na tela.
 */
final class McpSurfaces {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path stateDir;
    private final NotificationCenter notifications;
    private final Object lock;

    McpSurfaces(Path stateDir, NotificationCenter notifications, Object lock) {
        this.stateDir = stateDir;
        this.notifications = notifications;
        this.lock = lock;
    }

    /**
     * A superfície mudou desde a aprovação? Então avisa e devolve {@code true}. Sem
     * aprovação ainda, esta passa a ser a aprovada.
     */
    boolean drifted(McpManager.Server server, List<Map<String, Object>> surface) throws IOException {
        String hash = hash(surface);
        String approved = approved().get(server.name());
        if (approved != null && !approved.equals(hash)) {
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
            return true;
        }
        if (approved == null) {
            save(server.name(), hash);
        }
        return false;
    }

    void save(String name, String hash) throws IOException {
        synchronized (lock) {
            Map<String, String> surfaces = approved();
            surfaces.put(name, hash);
            write(stateDir.resolve("mcp-surface.json"), surfaces);
        }
    }

    Map<String, String> firstSeen() {
        return read(stateDir.resolve("mcp-seen.json"));
    }

    void seen(Map<String, String> seen) throws IOException {
        write(stateDir.resolve("mcp-seen.json"), seen);
    }

    static String hash(List<Map<String, Object>> surface) throws IOException {
        List<Map<String, Object>> sorted = new ArrayList<>(surface);
        sorted.sort(Comparator.comparing(tool -> String.valueOf(tool.get("name"))));
        List<Map<String, Object>> relevant = sorted.stream().map(tool -> {
            Map<String, Object> row = new TreeMap<>();
            row.put("name", tool.get("name"));
            row.put("description", tool.get("description"));
            row.put("inputSchema", tool.get("inputSchema"));
            row.put("annotations", tool.get("annotations"));
            return row;
        }).toList();
        try {
            byte[] canonical = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(relevant);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> approved() {
        return read(stateDir.resolve("mcp-surface.json"));
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
}
