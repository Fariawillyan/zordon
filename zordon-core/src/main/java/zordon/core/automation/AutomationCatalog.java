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
package zordon.core.automation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.memory.AutomationStateStore;

/**
 * As automações conhecidas: as do diretório, as propostas à espera de aprovação e o
 * estado do gatilho de cada uma. Sob o lock do {@link AutomationEngine}.
 */
final class AutomationCatalog {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);

    private final Path directory;
    private final AutomationStateStore states;
    private final AutomationValidation validation;
    private final WorkflowEngine.Notifier notifier;
    private final Map<String, AutomationSpec> specs = new LinkedHashMap<>();
    private final Map<String, AutomationSpec> proposals = new LinkedHashMap<>();
    private final Map<String, TriggerState> triggers = new LinkedHashMap<>();
    private final List<Map<String, Object>> invalid = new ArrayList<>();

    AutomationCatalog(Path directory, AutomationEngine.Stores stores, AutomationEngine.Engines engines) {
        this.directory = directory;
        this.states = stores.states();
        this.validation = new AutomationValidation(engines.tools(), engines.agents());
        this.notifier = engines.notifier();
    }

    Collection<AutomationSpec> specs() {
        return specs.values();
    }

    Optional<AutomationSpec> get(String id) {
        return Optional.ofNullable(specs.get(id));
    }

    TriggerState trigger(String id) {
        return triggers.get(id);
    }

    Instant lastFiredAt(String id) {
        return states.automationState(id).lastFiredAt();
    }

    void reload() {
        Map<String, AutomationSpec> loaded = new LinkedHashMap<>();
        invalid.clear();
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".toml")).sorted().toList()) {
                    try {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 262_144) {
                            throw new IllegalArgumentException("arquivo inválido ou maior que 256 KiB");
                        }
                        AutomationSpec spec = AutomationSpec.parseToml(Files.readString(file));
                        if (!file.getFileName().toString().equals(spec.id() + ".toml")) {
                            throw new IllegalArgumentException("nome do arquivo não corresponde ao id");
                        }
                        validate(spec);
                        loaded.put(spec.id(), spec);
                        if (!spec.equals(specs.get(spec.id()))) {
                            triggers.put(spec.id(), new TriggerState());
                        }
                    } catch (IOException | RuntimeException e) {
                        invalid.add(Map.of("id", file.getFileName().toString(), "name", file.getFileName().toString(),
                                "enabled", false, "reason", String.valueOf(e.getMessage())));
                    }
                }
            }
            specs.clear();
            specs.putAll(loaded);
            triggers.keySet().retainAll(loaded.keySet());
        } catch (IOException e) {
            log.warn("automações indisponíveis: {}", e.toString());
        }
    }

    Map<String, Object> propose(Map<String, Object> raw) {
        AutomationSpec spec = AutomationSpec.fromMap(raw);
        validate(spec);
        if (specs.containsKey(spec.id()) || Files.exists(directory.resolve(spec.id() + ".toml"))) {
            throw new IllegalArgumentException("já existe uma automação com este id");
        }
        if (proposals.size() >= 100) {
            throw new IllegalArgumentException("há 100 propostas pendentes; recuse ou aprove antes de criar mais");
        }
        String id = "proposal-" + UUID.randomUUID();
        proposals.put(id, spec);
        notifier.notify(spec, "Automação aguardando sua aprovação", summary(spec), "info");
        return Map.of("proposalId", id, "summary", summary(spec));
    }

    String approve(String proposalId) {
        AutomationSpec spec = Optional.ofNullable(proposals.get(proposalId))
                .orElseThrow(() -> new IllegalArgumentException("proposta desconhecida"));
        validate(spec);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(spec.id() + ".toml"), spec.toToml(), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new IllegalArgumentException("não foi possível criar a automação: " + e.getMessage(), e);
        }
        proposals.remove(proposalId);
        reload();
        return spec.id();
    }

    boolean reject(String proposalId) {
        return proposals.remove(proposalId) != null;
    }

    boolean enable(String id, boolean enabled) {
        require(id);
        states.automationDisabled(id, !enabled, enabled ? "ativada na tela" : "desativada na tela");
        triggers.put(id, new TriggerState());
        return enabled;
    }

    boolean enabled(AutomationSpec spec) {
        AutomationStateStore.State state = states.automationState(spec.id());
        return !state.disabled() && (spec.enabled() || "ativada na tela".equals(state.reason()));
    }

    boolean hasConditions() {
        return specs.values().stream().anyMatch(spec -> enabled(spec) && spec.trigger() instanceof AutomationSpec.Condition);
    }

    /** @param running se a automação está rodando agora */
    Map<String, Object> list(Predicate<String> running) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AutomationSpec spec : specs.values()) {
            AutomationStateStore.State state = states.automationState(spec.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", spec.id());
            row.put("name", spec.name());
            row.put("trigger", spec.trigger().summary());
            row.put("enabled", enabled(spec));
            row.put("failures", state.failures());
            row.put("running", running.test(spec.id()));
            if (state.lastFiredAt() != null) {
                row.put("lastFiredAt", state.lastFiredAt().toString());
            }
            if (state.reason() != null) {
                row.put("reason", state.reason());
            }
            rows.add(row);
        }
        rows.addAll(invalid);
        return Map.of("automations", rows, "proposals", proposals.entrySet().stream().map(entry ->
                Map.of("proposalId", entry.getKey(), "id", entry.getValue().id(), "name", entry.getValue().name(),
                        "summary", summary(entry.getValue()))).toList());
    }

    /** Contagens para o diagnóstico. */
    Map<String, Object> counts() {
        long enabled = specs.values().stream().filter(this::enabled).count();
        return Map.of("enabled", enabled, "disabled", specs.size() - enabled, "invalid", invalid.size(),
                "proposals", proposals.size());
    }

    AutomationSpec require(String id) {
        return Optional.ofNullable(specs.get(id)).orElseThrow(() -> new IllegalArgumentException("automação desconhecida: " + id));
    }

    void validate(AutomationSpec spec) {
        validation.check(spec);
    }

    private static String summary(AutomationSpec spec) {
        // A tela mostra também argumentos, mensagens, condições, retries e limites antes de aprovar.
        return spec.name() + " — " + spec.trigger().summary() + "\n\n" + spec.toToml();
    }
}
