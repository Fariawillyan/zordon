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
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import zordon.ai.ModelRole;
import zordon.api.security.RiskLevel;
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
                loaded.add(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "builtin"));
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
                    AgentProfile agent = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
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

    static AgentProfile parse(String text, String source) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException("TOML inválido: " + toml.errors().getFirst().toString());
        }
        String id = id(toml);
        String prompt = prompt(toml);
        return new AgentProfile(id, toml.getString("name") == null ? id : toml.getString("name"),
                toml.getString("description") == null ? "" : toml.getString("description"), prompt.strip(),
                scope(toml), ceiling(toml), role(toml), budget(toml), source);
    }

    private static String id(TomlParseResult toml) {
        String id = toml.getString("id");
        if (id == null || !id.matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("id ausente ou inválido (a-z, 0-9, -, até 40)");
        }
        return id;
    }

    private static String prompt(TomlParseResult toml) {
        String prompt = toml.getString("prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt ausente");
        }
        if (prompt.length() > 8_000) {
            throw new IllegalArgumentException("prompt maior que 8.000 caracteres");
        }
        return prompt;
    }

    private static ToolScope scope(TomlParseResult toml) {
        TomlTable tools = toml.getTable("tools");
        return tools == null ? ToolScope.ALL : new ToolScope(
                strings(tools.getArray("include"), List.of("*")),
                strings(tools.getArray("exclude"), List.of()),
                strings(tools.getArray("pinned"), List.of()));
    }

    private static RiskLevel ceiling(TomlParseResult toml) {
        String ceiling = toml.getString("permissions.ceiling");
        try {
            return ceiling == null ? RiskLevel.YELLOW : RiskLevel.valueOf(ceiling.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("permissions.ceiling deve ser GREEN, YELLOW ou RED");
        }
    }

    private static ModelRole role(TomlParseResult toml) {
        String role = toml.getString("model.role");
        try {
            return role == null ? ModelRole.CONVERSATION : ModelRole.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("model.role desconhecido: " + role);
        }
    }

    private static Budget budget(TomlParseResult toml) {
        Budget defaults = Budget.DEFAULT;
        try {
            Long steps = toml.getLong("budget.maxSteps");
            Long calls = toml.getLong("budget.maxToolCalls");
            Long tokens = toml.getLong("budget.maxTokens");
            String clock = toml.getString("budget.wallClock");
            return new Budget(steps == null ? defaults.maxSteps() : steps.intValue(),
                    calls == null ? defaults.maxToolCalls() : calls.intValue(),
                    tokens == null ? defaults.maxTokens() : tokens,
                    clock == null ? defaults.wallClock() : Duration.parse(clock));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("budget.wallClock deve ser uma duração ISO-8601, como PT5M");
        }
    }

    private static List<String> strings(TomlArray array, List<String> fallback) {
        if (array == null) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(array.getString(i));
        }
        return List.copyOf(out);
    }
}
