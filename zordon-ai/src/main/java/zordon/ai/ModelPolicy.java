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
package zordon.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Qual modelo e qual esforço cada papel usa. */
public record ModelPolicy(Map<ModelRole, ModelChoice> byRole) {

    /**
     * Quem atende um papel: provider, modelo e, opcionalmente, esforço.
     *
     * <p>Esforço ausente significa "o padrão do provider" — ver {@link AiRequest#effortIfAny()}.
     */
    public record ModelChoice(String provider, String model, Effort effort) {
        public ModelChoice {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
        }

        public java.util.Optional<Effort> effortIfAny() {
            return java.util.Optional.ofNullable(effort);
        }
    }

    public ModelPolicy {
        byRole = Map.copyOf(byRole);
    }

    /**
     * Provider usado quando não há configuração: a assinatura, nunca a chave paga por
     * uso (SPEC-018 §3). Quem quiser a API declara-a no {@code config.toml}.
     */
    public static final String DEFAULT_PROVIDER = "claude";

    /** A API por chave: só a reserva do padrão, e o último da ordem de preferência. */
    public static final String API_PROVIDER = "anthropic";

    /**
     * Padrão sem {@code config.toml}: Opus para julgamento, Haiku para volume
     * (docs/specs/core/design.md §1), pela assinatura. Com configuração, estes valores
     * não valem.
     */
    public static ModelPolicy defaults() {
        Map<ModelRole, ModelChoice> roles = new EnumMap<>(ModelRole.class);
        roles.put(ModelRole.CONVERSATION, new ModelChoice(DEFAULT_PROVIDER, "opus", Effort.HIGH));
        roles.put(ModelRole.ROUTING, new ModelChoice(DEFAULT_PROVIDER, "haiku", Effort.LOW));
        roles.put(ModelRole.AGENT_HEAVY, new ModelChoice(DEFAULT_PROVIDER, "opus", Effort.XHIGH));
        roles.put(ModelRole.AGENT_LIGHT, new ModelChoice(DEFAULT_PROVIDER, "sonnet", Effort.MEDIUM));
        roles.put(ModelRole.SUMMARIZE, new ModelChoice(DEFAULT_PROVIDER, "haiku", Effort.LOW));
        roles.put(ModelRole.FALLBACK, new ModelChoice(API_PROVIDER, "claude-sonnet-5", Effort.MEDIUM));
        return new ModelPolicy(roles);
    }

    public java.util.Optional<ModelChoice> find(ModelRole role) {
        return java.util.Optional.ofNullable(byRole.get(role));
    }

    public ModelChoice forRole(ModelRole role) {
        ModelChoice choice = byRole.get(role);
        if (choice == null) {
            throw new IllegalStateException("nenhum modelo configurado para o papel " + role);
        }
        return choice;
    }
}
