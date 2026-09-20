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

import java.util.Objects;
import zordon.ai.ModelRole;
import zordon.api.security.RiskLevel;

/**
 * Um agente: prompt, escopo, teto, modelo e orçamento sobre o mesmo laço (Agentes
 * §1). Não é código: é o que está num {@code .toml}.
 *
 * @param source {@code builtin} ou o caminho do arquivo do usuário
 */
public record AgentProfile(String id, String name, String description, String prompt, ToolScope tools,
        RiskLevel ceiling, ModelRole role, Budget budget, String source) {

    public AgentProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(ceiling, "ceiling");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(budget, "budget");
    }
}
