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
package zordon.core.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Valida e normaliza uma linha do stream de eventos do Docker. */
final class DockerEventParser {

    private static final Logger log = LoggerFactory.getLogger(DockerEventParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static Optional<Map<String, Object>> parse(String line) {
        JsonNode event;
        try {
            event = JSON.readTree(line);
        } catch (java.io.IOException e) {
            log.warn("eventos do Docker: linha que não é JSON ignorada");
            return Optional.empty();
        }
        String action = event.path("Action").asText(event.path("status").asText(""));
        if (!DockerEvents.ACTIONS.contains(action)) {
            return Optional.empty();
        }
        JsonNode attributes = event.path("Actor").path("Attributes");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("container", attributes.path("name").asText(event.path("id").asText("?")));
        payload.put("image", attributes.path("image").asText(event.path("from").asText("")));
        payload.put("action", action.startsWith("health_status: ") ? action.substring("health_status: ".length())
                : action);
        if (attributes.hasNonNull("exitCode")) {
            payload.put("exitCode", attributes.path("exitCode").asInt());
        }
        long seconds = event.path("time").asLong(0);
        payload.put("at", (seconds > 0 ? Instant.ofEpochSecond(seconds) : Instant.now()).toString());
        return Optional.of(payload);
    }

    private DockerEventParser() {}
}
