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
package zordon.api.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Um evento já carimbado pelo barramento.
 *
 * <p>O {@code seq} é monotônico por inicialização do núcleo e é o que torna a
 * reconexão correta por construção (ADR-0011): o cliente diz até onde viu, e o
 * núcleo reenvia o resto ou declara que não há continuidade.
 *
 * <p>Identificadores de correlação ({@code turnId}, {@code agentId}, {@code runId})
 * viajam dentro de {@code payload}. Mantê-los fora do envelope evita três campos
 * nulos atravessando a fronteira de módulo em todo evento que não tem turno.
 */
public record EventEnvelope(long seq, Instant ts, EventType type, Map<String, Object> payload) {

    public EventEnvelope {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(type, "type");
        if (seq < 0) {
            throw new IllegalArgumentException("seq não pode ser negativo");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public String topic() {
        return type.topic();
    }
}
