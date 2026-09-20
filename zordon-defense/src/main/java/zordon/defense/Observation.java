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
package zordon.defense;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import zordon.api.security.Effect;

/**
 * O que o motor viu, já normalizado (SPEC-026). Uma observação é barata de montar:
 * ela é criada no caminho quente, e o detector é que decide se vira sinal.
 *
 * @param kind {@code tool.call}, {@code tool.result}, {@code model.output},
 *     {@code mcp.drift}, {@code integrity.self}, {@code integrity.config} ou
 *     {@code integrity.audit}
 * @param text o conteúdo observado (resultado, resposta), já cortado pelo chamador
 */
public record Observation(String kind, Subject subject, String actor, String turnId, String tool, String text,
        Set<Effect> declaredEffects, Set<Effect> actionEffects, String decision, String reason, boolean tainted,
        Map<String, Object> data, Instant ts) {

    public Observation {
        declaredEffects = declaredEffects == null ? Set.of() : Set.copyOf(declaredEffects);
        actionEffects = actionEffects == null ? Set.of() : Set.copyOf(actionEffects);
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static Observation of(String kind, Subject subject, Instant ts) {
        return new Observation(kind, subject, null, null, null, null, null, null, null, null, false, null, ts);
    }

    public Observation withText(String value) {
        return new Observation(kind, subject, actor, turnId, tool, value, declaredEffects, actionEffects, decision,
                reason, tainted, data, ts);
    }

    public Observation withData(Map<String, Object> value) {
        return new Observation(kind, subject, actor, turnId, tool, text, declaredEffects, actionEffects, decision,
                reason, tainted, value, ts);
    }
}
