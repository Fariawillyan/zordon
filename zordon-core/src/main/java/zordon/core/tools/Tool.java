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
package zordon.core.tools;

import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.security.Gatekeeper;

/**
 * Uma capacidade do Zordon (SPEC-016). Declara o que é; monta a ação a partir
 * dos argumentos, com o resumo gerado aqui e nunca pelo modelo; e só executa com
 * uma autorização do {@link Gatekeeper}.
 */
@Spec("SPEC-016")
public interface Tool {

    String name();

    String description();

    RiskLevel baseRisk();

    Set<Effect> effects();

    /** Valida os argumentos e descreve a ação. Nada acontece aqui. */
    ActionDescriptor describe(Map<String, Object> args) throws ToolException;

    ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception;

    /** Com o turno de origem, para quem precisa de procedência (SPEC-021). */
    default ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args, String turnId) throws Exception {
        return run(permit, args);
    }

    /** Falso para ferramentas que só o usuário pede, como gravar memória (SPEC-021 §3). */
    default boolean modelVisible() {
        return true;
    }

    /** O esquema dos argumentos, em JSON Schema, para o modelo saber o que mandar (SPEC-019). */
    default Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    /** Um argumento de texto obrigatório, no formato de {@link #inputSchema()}. */
    static Map<String, Object> schema(String argument, String description) {
        return Map.of("type", "object",
                "properties", Map.of(argument, Map.of("type", "string", "description", description)),
                "required", java.util.List.of(argument));
    }

    static String text(Map<String, Object> args, String key) throws ToolException {
        if (!(args.get(key) instanceof String value) || value.isBlank()) {
            throw new ToolException("falta o argumento " + key);
        }
        return value.strip();
    }
}
