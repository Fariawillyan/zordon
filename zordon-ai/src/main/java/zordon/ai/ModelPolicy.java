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

    /** Provider usado quando não há configuração: o do M1, para ninguém perder o que funcionava. */
    public static final String DEFAULT_PROVIDER = "anthropic";

    /**
     * Padrão sem {@code config.toml}: Opus para julgamento, Haiku para volume
     * (docs/specs/core/design.md §1). Com configuração, estes valores não valem.
     */
    public static ModelPolicy defaults() {
        Map<ModelRole, ModelChoice> roles = new EnumMap<>(ModelRole.class);
        roles.put(ModelRole.CONVERSATION, new ModelChoice(DEFAULT_PROVIDER, "claude-opus-5", Effort.HIGH));
        roles.put(ModelRole.ROUTING, new ModelChoice(DEFAULT_PROVIDER, "claude-haiku-4-5", Effort.LOW));
        roles.put(ModelRole.AGENT_HEAVY, new ModelChoice(DEFAULT_PROVIDER, "claude-opus-5", Effort.XHIGH));
        roles.put(ModelRole.AGENT_LIGHT, new ModelChoice(DEFAULT_PROVIDER, "claude-sonnet-5", Effort.MEDIUM));
        roles.put(ModelRole.SUMMARIZE, new ModelChoice(DEFAULT_PROVIDER, "claude-haiku-4-5", Effort.LOW));
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
