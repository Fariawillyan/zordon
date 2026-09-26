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
package zordon.core.agents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * Os agentes: os embutidos e os de {@code ~/.zordon/agents/*.toml}, que vencem pelo
 * mesmo {@code id} (SPEC-022). Relido a cada consulta: agente novo não pede
 * reinício, e um arquivo ruim não derruba os outros.
 */
@Spec("SPEC-022")
public final class AgentRegistry {

    public static final String GENERAL = "zordon";
    static final List<String> BUILTIN = List.of("zordon", "system", "developer", "research", "automation", "rag",
            "spec", "architecture", "java", "testing", "codereview", "documentation");

    /** Um arquivo que não virou agente, e por quê. */
    public record Invalid(String file, String reason) {}

    public record Snapshot(List<AgentProfile> agents, List<Invalid> invalid) {}

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Path userDir;
    private final List<AgentProfile> builtins;

    public AgentRegistry(Path userDir) {
        this.userDir = userDir;
        List<AgentProfile> loaded = new ArrayList<>();
        for (String id : BUILTIN) {
            try (InputStream in = AgentRegistry.class.getResourceAsStream("/agents/" + id + ".toml")) {
                if (in == null) {
                    throw new IllegalStateException("agente embutido ausente do empacotamento: " + id);
                }
                loaded.add(AgentParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "builtin"));
            } catch (IOException e) {
                throw new IllegalStateException("agente embutido ilegível: " + id, e);
            }
        }
        this.builtins = List.copyOf(loaded);
    }

    public Snapshot snapshot() {
        Map<String, AgentProfile> byId = new LinkedHashMap<>();
        builtins.forEach(agent -> byId.put(agent.id(), agent));
        List<Invalid> invalid = new ArrayList<>();
        if (userDir != null && Files.isDirectory(userDir)) {
            List<Path> files;
            try (Stream<Path> listing = Files.list(userDir)) {
                files = listing.filter(file -> file.getFileName().toString().endsWith(".toml")).sorted().toList();
            } catch (IOException e) {
                files = List.of();
                invalid.add(new Invalid(userDir.toString(), "pasta ilegível: " + e.getMessage()));
            }
            java.util.Set<String> fromUser = new java.util.HashSet<>();
            for (Path file : files) {
                try {
                    AgentProfile agent = AgentParser.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
                    if (!fromUser.add(agent.id())) {
                        invalid.add(new Invalid(file.toString(), "id repetido: " + agent.id()
                                + " (vale o primeiro em ordem alfabética)"));
                        continue;
                    }
                    byId.put(agent.id(), agent);
                } catch (IOException | RuntimeException e) {
                    invalid.add(new Invalid(file.toString(), e.getMessage()));
                }
            }
        }
        invalid.forEach(bad -> log.warn("agente ignorado: {} — {}", bad.file(), bad.reason()));
        return new Snapshot(List.copyOf(byId.values()), List.copyOf(invalid));
    }

    public List<AgentProfile> list() {
        return snapshot().agents();
    }

    public Optional<AgentProfile> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.strip().toLowerCase(Locale.ROOT).replaceFirst("agent$", "");
        return list().stream().filter(agent -> agent.id().equals(wanted)
                || agent.name() != null && agent.name().toLowerCase(Locale.ROOT).equals(id.strip().toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    public AgentProfile general() {
        return find(GENERAL).orElseThrow();
    }

}
