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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    private ProviderRegistry(Map<String, AiProvider> ready, Map<String, String> unavailable, ModelPolicy roles) {
        this.ready = Map.copyOf(ready);
        this.unavailable = Map.copyOf(unavailable);
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    /** Monta os providers da configuração, resolvendo as chaves no ambiente. */
    public static ProviderRegistry build(AiSettings settings, Pricing pricing, Map<String, String> environment) {
        Map<String, AiProvider> ready = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>(settings.rejected());

        settings.providers().forEach((id, config) -> {
            try {
                ready.put(id, create(config, pricing, environment));
            } catch (IllegalStateException | IllegalArgumentException e) {
                unavailable.put(id, e.getMessage());
            }
        });
        ProviderRegistry registry = new ProviderRegistry(ready, unavailable, settings.roles());
        registry.describe().forEach((id, state) -> log.info("provider {}: {}", id, state));
        return registry;
    }

    /** Registro com providers prontos — para testes e composições que não leem configuração. */
    public static ProviderRegistry of(Map<String, AiProvider> providers, ModelPolicy roles) {
        return new ProviderRegistry(providers, Map.of(), roles);
    }

    public Resolution select(ModelRole role) {
        String roleName = role.name().toLowerCase(Locale.ROOT);
        var choice = roles.find(role);
        if (choice.isEmpty()) {
            return new Resolution.Unresolved(
                    null, "nenhum modelo configurado para o papel '" + roleName + "' em ~/.zordon/config.toml");
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

    private static AiProvider create(ProviderConfig config, Pricing pricing, Map<String, String> environment) {
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
        };
    }

    private static String requireKey(SecretRef reference, Map<String, String> environment) {
        return reference.resolve(environment).orElseThrow(() -> new IllegalStateException(
                "defina " + reference.variable() + " (em ~/.zordon/secrets.env para o serviço)"));
    }
}
