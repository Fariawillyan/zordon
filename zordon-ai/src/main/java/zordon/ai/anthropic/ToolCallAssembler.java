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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import zordon.ai.AiException;
import zordon.ai.AiStreamListener;
import zordon.ai.ContentBlock;

/** Junta os pedaços de JSON de cada chamada de ferramenta do streaming, bloco por bloco. */
final class ToolCallAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiStreamListener listener;
    private final Map<Long, PendingToolCall> pending = new HashMap<>();
    private final List<ContentBlock.ToolUse> completed = new ArrayList<>();

    ToolCallAssembler(AiStreamListener listener) {
        this.listener = listener;
    }

    void start(long index, String id, String name) {
        pending.put(index, new PendingToolCall(id, name, new StringBuilder()));
    }

    void append(long index, String partialJson) {
        Optional.ofNullable(pending.get(index)).ifPresent(call -> call.json().append(partialJson));
    }

    void finish(long index) {
        PendingToolCall call = pending.remove(index);
        if (call == null) {
            return;
        }
        try {
            String json = call.json().isEmpty() ? "{}" : call.json().toString();
            ContentBlock.ToolUse use = new ContentBlock.ToolUse(call.id(), call.name(), MAPPER.readTree(json));
            completed.add(use);
            listener.onToolUse(use);
        } catch (JsonProcessingException e) {
            // Argumento inválido volta ao modelo como erro de ferramenta; abortar o
            // turno inteiro por causa disto seria desproporcional.
            listener.onError(new AiException(
                    AiException.Kind.INVALID_REQUEST,
                    "argumentos inválidos para " + call.name(), e));
        }
    }

    List<ContentBlock.ToolUse> completed() {
        return completed;
    }

    private record PendingToolCall(String id, String name, StringBuilder json) {}
}
