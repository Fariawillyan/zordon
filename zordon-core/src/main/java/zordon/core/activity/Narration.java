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
package zordon.core.activity;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Uma frase que o Zordon vai dizer. Vem de um modelo de frase por tipo de evento
 * ou é a resposta de um turno de voz — nunca texto de raciocínio (ADR-0029).
 */
public record Narration(String text, Priority priority, String category) {

    public enum Priority {
        /** Mudança de etapa: agrupa, espera a vez, pode ser descartada. */
        NORMAL,
        /** Resultado ou erro: não espera. */
        HIGH,
        /** Pedido de autorização: passa na frente de tudo. */
        AUTHORIZATION;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Narration {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(category, "category");
        if (text.isBlank()) {
            throw new IllegalArgumentException("narração vazia");
        }
    }

    public Map<String, Object> payload() {
        return Map.of("text", text, "priority", priority.wire(), "category", category);
    }
}
