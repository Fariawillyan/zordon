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
package zordon.ai.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import zordon.ai.ToolSpec;

/** Ferramentas e argumentos de chamada: o JSON Schema do Zordon como valores do SDK. */
final class AnthropicTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicTools() {}

    static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        fields(spec.inputSchema().get("properties")).forEach(properties::putAdditionalProperty);

        List<String> required = new ArrayList<>();
        JsonNode declared = spec.inputSchema().get("required");
        if (declared != null && declared.isArray()) {
            declared.forEach(field -> required.add(field.asText()));
        }
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(required)
                        .build())
                .build();
    }

    static ToolUseBlockParam.Input toInput(JsonNode arguments) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        fields(arguments).forEach(input::putAdditionalProperty);
        return input.build();
    }

    /** Campos de um objeto JSON como valores do SDK; objeto ausente vira vazio. */
    private static Map<String, JsonValue> fields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, JsonValue> values = new LinkedHashMap<>();
        node.properties().forEach(entry ->
                values.put(entry.getKey(), JsonValue.from(MAPPER.convertValue(entry.getValue(), Object.class))));
        return values;
    }
}
