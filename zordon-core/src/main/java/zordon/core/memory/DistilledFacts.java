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
package zordon.core.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.memory.FactKind;
import zordon.memory.NewFact;
import zordon.security.Redactor;

/** O que o modelo devolveu, conferido: tipo conhecido, teto de confiança e nada de segredo. */
final class DistilledFacts {

    private static final Logger log = LoggerFactory.getLogger(Distiller.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final Redactor redactor;
    private final Clock clock;

    DistilledFacts(Redactor redactor, Clock clock) {
        this.redactor = redactor;
        this.clock = clock;
    }

    /** Valida o que o modelo devolveu; o que não passa é descartado, com log. */
    List<NewFact> parse(String text, String turnId) throws Exception {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("resposta sem array JSON");
        }
        List<Map<String, Object>> items = json.readValue(text.substring(start, end + 1),
                new TypeReference<List<Map<String, Object>>>() { });
        List<NewFact> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            try {
                FactKind kind = FactKind.valueOf(String.valueOf(item.get("kind")).toUpperCase(Locale.ROOT));
                String subject = String.valueOf(item.getOrDefault("subject", "")).strip();
                String content = String.valueOf(item.getOrDefault("content", "")).strip();
                boolean corrects = Boolean.TRUE.equals(item.get("corrects"));
                double confidence = item.get("confidence") instanceof Number number ? number.doubleValue() : 0.6;
                confidence = Math.max(0, Math.min(confidence, corrects ? Distiller.MAX_CORRECTION : Distiller.MAX_CONFIDENCE));
                if (redactor.containsSecret(subject) || redactor.containsSecret(content)) {
                    log.info("destilação do turno {}: fato com segredo descartado", turnId);
                    continue;
                }
                out.add(new NewFact(kind, subject, content, confidence, clock.instant(), null, turnId, "distill",
                        corrects));
            } catch (IllegalArgumentException e) {
                log.info("destilação do turno {}: fato inválido descartado ({})", turnId, e.getMessage());
            }
        }
        return out;
    }
}
