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
package zordon.core.notify;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import zordon.api.security.Severity;
import zordon.api.trace.Spec;

/**
 * Uma comunicação iniciada pelo Zordon, com as oito respostas do contrato de
 * explicação (docs/security/communication.md §4, SPEC-015 CA-5). Todas são
 * obrigatórias e montadas por código, nunca pelo modelo.
 *
 * @param channel {@code SECURITY}, {@code DEFENSE}, {@code AI_DEFENSE}, {@code NETWORK} ou {@code SYSTEM}
 * @param eventId liga ao {@code SecurityEvent}; {@code null} quando não há
 */
@Spec("SPEC-015")
public record ZordonMessage(
        String id,
        Severity severity,
        String channel,
        String title,
        String whatHappened,
        String whySuspicious,
        String detectedBy,
        String actionTaken,
        String affectedResource,
        boolean reversible,
        String currentState,
        List<String> options,
        String eventId,
        Instant ts) {

    public ZordonMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(severity, "severity");
        required(channel, "channel");
        required(title, "title");
        required(whatHappened, "whatHappened");
        required(whySuspicious, "whySuspicious");
        required(detectedBy, "detectedBy");
        required(actionTaken, "actionTaken");
        required(affectedResource, "affectedResource");
        required(currentState, "currentState");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (options.isEmpty() || options.stream().anyMatch(option -> option == null || option.isBlank())) {
            throw new IllegalArgumentException("options: o usuário precisa saber o que pode fazer");
        }
        Objects.requireNonNull(ts, "ts");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório (Comunicação §4)");
        }
    }

    public Map<String, Object> payload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messageId", id);
        out.put("severity", severity.wire());
        out.put("channel", channel);
        out.put("title", title);
        out.put("whatHappened", whatHappened);
        out.put("whySuspicious", whySuspicious);
        out.put("detectedBy", detectedBy);
        out.put("actionTaken", actionTaken);
        out.put("affectedResource", affectedResource);
        out.put("reversible", reversible);
        out.put("currentState", currentState);
        out.put("options", options);
        if (eventId != null) {
            out.put("eventId", eventId);
        }
        out.put("ts", ts.toString());
        return out;
    }
}
