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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiProvider;
import zordon.ai.ModelPolicy;
import zordon.ai.ModelRole;
import zordon.ai.Pricing;
import zordon.ai.anthropic.AnthropicProvider;
import zordon.ai.openai.OpenAiCompatibleProvider;
import zordon.api.trace.Spec;

/**
 * Os providers configurados e quem atende cada papel.
 *
 * <p>É o único lugar que conhece os adaptadores. O núcleo pede "o provider do papel
 * {@code conversation}" e recebe um {@link AiProvider} — ou o motivo de não haver
 * um, em palavras que o usuário consegue seguir (ADR-0026).
 */
@Spec("SPEC-004")
public final class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /** O que atende um papel. */
    public record Selection(String providerId, AiProvider provider, ModelPolicy.ModelChoice choice) {}

    /** Resultado de uma seleção: um provider, ou o motivo de não haver. */
    public sealed interface Resolution {

        record Selected(Selection selection) implements Resolution {}

        /**
         * @param providerId o provider que o papel pede, quando há um — é ele que a
         *     reserva substitui e que a tela nomeia
         */
        record Unresolved(String providerId, String reason) implements Resolution {}
    }

    private final Map<String, AiProvider> ready;
    private final Map<String, String> unavailable;
    private final ModelPolicy roles;
    /** Ids prontos na ordem de preferência: assinatura, local, e a chave paga por último. */
    private final List<String> preference;
    /** Modelo de cada provider quando o papel não nomeia um; sem entrada, o Zordon não adivinha. */
    private final Map<String, String> defaultModels;

    private ProviderRegistry(Map<String, AiProvider> ready, Map<String, String> unavailable, ModelPolicy roles,
            List<String> preference, Map<String, String> defaultModels) {
        this.ready = Map.copyOf(ready);
        this.unavailable = Map.copyOf(unavailable);
        this.roles = Objects.requireNonNull(roles, "roles");
        this.preference = List.copyOf(preference);
        this.defaultModels = Map.copyOf(defaultModels);
    }

    /** Monta os providers da configuração, resolvendo as chaves no ambiente. */
    public static ProviderRegistry build(AiSettings settings, Pricing pricing, Map<String, String> environment) {
        return build(settings, pricing, environment, null);
    }

    /** @param cli quem roda os providers por assinatura; {@code null} deixa-os indisponíveis */
    public static ProviderRegistry build(AiSettings settings, Pricing pricing, Map<String, String> environment,
            zordon.ai.cli.CliRunner cli) {
        Map<String, AiProvider> ready = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>(settings.rejected());
        Map<String, String> models = new LinkedHashMap<>();

        // Na ordem de preferência, e não na do arquivo: quem tenta primeiro é a
        // assinatura, e a chave paga por uso fica por último (SPEC-018 §3).
        for (ProviderConfig config : settings.providers().values().stream()
                .sorted(Comparator.comparingInt(ProviderConfig::precedence).thenComparing(ProviderConfig::id))
                .toList()) {
            try {
                ready.put(config.id(), create(config, pricing, environment, cli));
                if (config.defaultModel() != null) {
                    models.put(config.id(), config.defaultModel());
                }
            } catch (IllegalStateException | IllegalArgumentException e) {
                unavailable.put(config.id(), e.getMessage());
            }
        }
        ProviderRegistry registry = new ProviderRegistry(ready, unavailable, settings.roles(),
                new ArrayList<>(ready.keySet()), models);
        registry.describe().forEach((id, state) -> log.info("provider {}: {}", id, state));
        return registry;
    }

    /** Registro com providers prontos — para testes e composições que não leem configuração. */
    public static ProviderRegistry of(Map<String, AiProvider> providers, ModelPolicy roles) {
        return new ProviderRegistry(providers, Map.of(), roles, new ArrayList<>(providers.keySet()), Map.of());
    }

    public Resolution select(ModelRole role) {
        String roleName = role.name().toLowerCase(Locale.ROOT);
        var choice = roles.find(role);
        if (choice.isEmpty()) {
            // Papel não declarado: em vez de desistir, o Zordon segue a ordem de
            // preferência. Embeddings fica de fora — modelo de conversa não serve, e
            // adivinhar aqui daria um vetor errado em silêncio.
            Optional<Selection> preferred = role == ModelRole.EMBEDDINGS ? Optional.empty() : preferred(null);
            return preferred.<Resolution>map(Resolution.Selected::new).orElseGet(() -> new Resolution.Unresolved(
                    null, "nenhum modelo configurado para o papel '" + roleName + "' em ~/.zordon/config.toml"));
        }
        String providerId = choice.get().provider();
        AiProvider provider = ready.get(providerId);
        if (provider != null) {
            return new Resolution.Selected(new Selection(providerId, provider, choice.get()));
        }
        String reason = unavailable.get(providerId);
        return new Resolution.Unresolved(providerId, reason != null
                ? "provider '" + providerId + "' indisponível: " + reason
                : "o papel '" + roleName + "' aponta para o provider '" + providerId
                        + "', que não existe em ~/.zordon/config.toml");
    }

    /**
     * O primeiro provider pronto da ordem de preferência — assinatura, servidor local,
     * e só então a chave paga por uso (SPEC-018 §3).
     *
     * @param excluding o provider que acabou de falhar, ou {@code null}; ele é pulado,
     *     porque repetir o mesmo daria o mesmo erro
     */
    public Optional<Selection> preferred(String excluding) {
        for (String id : preference) {
            String model = defaultModels.get(id);
            if (id.equals(excluding) || model == null) {
                continue;   // sem modelo conhecido, o Zordon não adivinha o nome
            }
            return Optional.of(new Selection(id, ready.get(id), new ModelPolicy.ModelChoice(id, model, null)));
        }
        return Optional.empty();
    }

    /** A ordem em que os providers prontos são tentados, para o diagnóstico e para a tela. */
    public List<String> preference() {
        return preference;
    }

    /**
     * Estado de cada provider, para o log de inicialização e para o diagnóstico.
     *
     * <p>"Configurado", e não "pronto": ter a chave não prova que ela vale, e
     * validar custaria uma chamada de rede a cada inicialização. O primeiro turno
     * — ou {@code set-api-key.sh --check} — é quem responde se ela funciona.
     */
    public Map<String, String> describe() {
        Map<String, String> states = new LinkedHashMap<>();
        ready.forEach((id, provider) ->
                states.put(id, provider.info().local() ? "configurado (local)" : "configurado"));
        unavailable.forEach((id, reason) -> states.put(id, "indisponível — " + reason));
        return states;
    }

    /**
     * Quem atende cada papel configurado, e se está pronto — para a tela de
     * Diagnóstico e para o aviso de "sem provider". Nomes, nunca chaves.
     */
    public Map<String, Map<String, Object>> describeRoles() {
        Map<String, Map<String, Object>> described = new LinkedHashMap<>();
        for (ModelRole role : ModelRole.values()) {
            roles.find(role).ifPresent(choice -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("provider", choice.provider());
                entry.put("model", choice.model());
                switch (select(role)) {
                    case Resolution.Selected selected -> entry.put("ready", true);
                    case Resolution.Unresolved unresolved -> {
                        entry.put("ready", false);
                        entry.put("reason", unresolved.reason());
                    }
                }
                described.put(role.name().toLowerCase(Locale.ROOT), entry);
            });
        }
        return described;
    }

    private static AiProvider create(ProviderConfig config, Pricing pricing, Map<String, String> environment,
            zordon.ai.cli.CliRunner cli) {
        return switch (config.type()) {
            case ANTHROPIC -> {
                SecretRef reference = config.apiKeyIfAny().orElse(new SecretRef("ANTHROPIC_API_KEY"));
                yield AnthropicProvider.create(requireKey(reference, environment), config.baseUrlIfAny(), pricing);
            }
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider(
                    config.id(),
                    config.baseUrl(),
                    // Chave é opcional aqui: um Ollama local não pede nenhuma.
                    config.apiKeyIfAny().map(reference -> requireKey(reference, environment)).orElse(null),
                    config.maxTokensParam(),
                    pricing);
            case CLAUDE_CLI -> {
                if (cli == null) {
                    throw new IllegalStateException("não há como rodar o claude neste núcleo");
                }
                if (!cli.available("claude")) {
                    throw new IllegalStateException("claude não está no catálogo de programas; instale o claude CLI"
                            + " (npm i -g @anthropic-ai/claude-code), entre com `claude` e reinicie o núcleo");
                }
                yield new zordon.ai.cli.ClaudeCliProvider(config.id(), cli);
            }
        };
    }

    private static String requireKey(SecretRef reference, Map<String, String> environment) {
        return reference.resolve(environment).orElseThrow(() -> new IllegalStateException(
                "defina " + reference.variable() + " (em ~/.zordon/secrets.env para o serviço)"));
    }
}
