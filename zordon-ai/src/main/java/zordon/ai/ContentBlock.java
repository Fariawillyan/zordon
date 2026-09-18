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
package zordon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Um bloco do conteúdo que o modelo produz ou recebe.
 *
 * <p>A saída de um modelo moderno não é uma string: é uma sequência de blocos de
 * tipos diferentes. Modelar isso como texto puro perde o raciocínio e as chamadas
 * de ferramenta ([Interfaces §2](../../../../docs/api/core-interfaces.md)).
 */
public sealed interface ContentBlock {

    record Text(String text) implements ContentBlock {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /** Resumo do raciocínio, quando o modelo o expõe. Nunca é tratado como resposta. */
    record Thinking(String summary) implements ContentBlock {
        public Thinking {
            Objects.requireNonNull(summary, "summary");
        }
    }

    /** Pedido de execução de ferramenta. Executar é decisão do núcleo, não do modelo. */
    record ToolUse(String callId, String tool, JsonNode arguments) implements ContentBlock {
        public ToolUse {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(tool, "tool");
        }
    }

    /** Resultado devolvido ao modelo. {@code isError} nunca é omitido: omitir trava o laço. */
    record ToolResult(String callId, String content, boolean isError) implements ContentBlock {
        public ToolResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(content, "content");
        }
    }
}
