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
package zordon.ai.registry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import zordon.ai.Effort;
import zordon.ai.ModelPolicy;
import zordon.ai.ModelRole;
import zordon.api.trace.Spec;

/**
 * Providers e papéis, lidos de {@code ~/.zordon/config.toml} (ADR-0026).
 *
 * <p>Configuração errada nunca impede o núcleo de subir: um provider inválido é
 * guardado com o motivo, e o turno que precisar dele diz ao usuário o que corrigir.
 * Recusar em silêncio seria pior do que falhar.
 */
@Spec("SPEC-004")
public record AiSettings(
        Map<String, ProviderConfig> providers, Map<String, String> rejected, ModelPolicy roles) {

    private static final Logger log = LoggerFactory.getLogger(AiSettings.class);

    public AiSettings {
        providers = Map.copyOf(providers);
        rejected = Map.copyOf(rejected);
    }

    /** Sem configuração, o comportamento do M1: Anthropic pela {@code ANTHROPIC_API_KEY}. */
    public static AiSettings defaults() {
        ProviderConfig anthropic = new ProviderConfig(
                ModelPolicy.DEFAULT_PROVIDER, ProviderType.ANTHROPIC, null, new SecretRef("ANTHROPIC_API_KEY"), null);
        return new AiSettings(Map.of(anthropic.id(), anthropic), Map.of(), ModelPolicy.defaults());
    }

    public static AiSettings load(Path file) {
        if (!Files.isReadable(file)) {
            return defaults();
        }
        try {
            TomlParseResult toml = Toml.parse(file);
            if (toml.hasErrors()) {
                log.warn("config.toml com erro, usando o padrão: {}", toml.errors().getFirst());
                return defaults();
            }
            TomlTable ai = toml.getTable("ai");
            return ai == null ? defaults() : fromTable(ai);
        } catch (IOException e) {
            log.warn("config.toml ilegível, usando o padrão: {}", e.getMessage());
            return defaults();
        }
    }

    static AiSettings fromTable(TomlTable ai) {
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        TomlTable declared = ai.getTable("providers");
        if (declared != null) {
            for (String id : declared.keySet()) {
                try {
                    providers.put(id, provider(id, declared.getTable(List.of(id))));
                } catch (IllegalArgumentException e) {
                    rejected.put(id, e.getMessage());
                    log.warn("provider '{}' recusado no config.toml: {}", id, e.getMessage());
                }
            }
        }
        return new AiSettings(providers, rejected, roles(ai.getTable("roles")));
    }

    private static ProviderConfig provider(String id, TomlTable table) {
        if (table == null) {
            throw new IllegalArgumentException("ai.providers." + id + " precisa ser uma tabela");
        }
        String apiKey = table.getString(List.of("api_key"));
        String baseUrl = table.getString(List.of("base_url"));
        return new ProviderConfig(
                id,
                ProviderType.parse(table.getString(List.of("type"))),
                baseUrl == null ? null : URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl),
                apiKey == null ? null : SecretRef.parse(apiKey),
                table.getString(List.of("max_tokens_param")));
    }

    private static ModelPolicy roles(TomlTable table) {
        Map<ModelRole, ModelPolicy.ModelChoice> roles = new EnumMap<>(ModelRole.class);
        if (table == null) {
            return new ModelPolicy(roles);
        }
        List<String> unknown = new ArrayList<>();
        for (String name : table.keySet()) {
            ModelRole role;
            try {
                role = ModelRole.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                unknown.add(name);
                continue;
            }
            TomlTable binding = table.getTable(List.of(name));
            String provider = binding == null ? null : binding.getString(List.of("provider"));
            String model = binding == null ? null : binding.getString(List.of("model"));
            if (provider == null || model == null) {
                log.warn("papel '{}' ignorado: precisa de provider e model", name);
                continue;
            }
            String effort = binding.getString(List.of("effort"));
            try {
                roles.put(role, new ModelPolicy.ModelChoice(
                        provider, model, effort == null ? null : Effort.valueOf(effort.toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException e) {
                log.warn("papel '{}' ignorado: effort '{}' não existe (use low, medium, high, xhigh ou max)",
                        name, effort);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("papéis desconhecidos ignorados no config.toml: {}", unknown);
        }
        return new ModelPolicy(roles);
    }
}
