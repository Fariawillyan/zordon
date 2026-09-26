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
package zordon.core.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** O JSON que um fluxo grava em cada passo da tarefa: o evento, os resultados e a definição aprovada. */
final class WorkflowJson {

    private static final ObjectMapper json = new ObjectMapper();

    private WorkflowJson() {}

    static String writeContext(Map<String, Map<String, Object>> context) {
        return write(new LinkedHashMap<>(context));
    }

    static String write(Map<String, Object> data) {
        try {
            return json.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("resultado não serializável", e);
        }
    }

    static Map<String, Object> read(String text) {
        if (text == null) {
            return Map.of();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            return Map.of();
        }
    }
}
