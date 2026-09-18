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
package zordon.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.ContentBlock;
import zordon.ai.Effort;
import zordon.ai.Role;
import zordon.ai.ToolSpec;

/**
 * Tradução de um {@link AiRequest} para o corpo de {@code POST /chat/completions}.
 *
 * <p>Os conceitos que não existem neste protocolo são omitidos, e não
 * improvisados: o pensamento adaptativo não tem equivalente, e a marca de cache é
 * desnecessária porque os servidores que fazem cache o fazem sozinhos pelo prefixo.
 */
final class ChatCompletionsRequests {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatCompletionsRequests() {}

    static ObjectNode toBody(AiRequest request, String maxTokensParam) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model());
        body.put("stream", true);
        // Sem isto, a maioria dos servidores não informa o consumo em streaming e o
        // custo teria de ser estimado.
        body.putObject("stream_options").put("include_usage", true);
        body.put(maxTokensParam, request.maxOutputTokens());

        // Esforço só quando pedido: um modelo que não raciocina devolve 400 ao
        // receber reasoning_effort (ADR-0026).
        request.effortIfAny().ifPresent(effort -> body.put("reasoning_effort", toReasoningEffort(effort)));

        ArrayNode messages = body.putArray("messages");
        if (!request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        request.messages().forEach(message -> appendMessage(messages, message));

        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            // Ordem estável: o cache automático por prefixo também depende dos bytes.
            request.tools().stream()
                    .sorted(Comparator.comparing(ToolSpec::name))
                    .forEach(tool -> {
                        ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                        function.put("name", tool.name());
                        function.put("description", tool.description());
                        function.set("parameters", tool.inputSchema());
                    });
        }
        return body;
    }

    /** O protocolo só conhece três níveis; acima de "high", "high" é o mais próximo que existe. */
    static String toReasoningEffort(Effort effort) {
        return switch (effort) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, XHIGH, MAX -> "high";
        };
    }

    /**
     * Uma mensagem do Zordon pode virar várias neste protocolo: cada resultado de
     * ferramenta é uma mensagem de papel {@code tool}, ligada à chamada pelo id.
     */
    private static void appendMessage(ArrayNode messages, AiMessage message) {
        StringBuilder text = new StringBuilder();
        List<ContentBlock.ToolUse> calls = new ArrayList<>();

        for (ContentBlock block : message.content()) {
            switch (block) {
                case ContentBlock.Text part -> text.append(part.text());
                case ContentBlock.ToolUse call -> calls.add(call);
                case ContentBlock.ToolResult result -> messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", result.callId())
                        .put("content", result.isError() ? "ERRO: " + result.content() : result.content());
                // O raciocínio é resumo para o usuário; não volta ao modelo.
                case ContentBlock.Thinking ignored -> { }
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            return;
        }
        ObjectNode node = messages.addObject().put("role", message.role() == Role.USER ? "user" : "assistant");
        if (text.isEmpty()) {
            node.putNull("content");
        } else {
            node.put("content", text.toString());
        }
        if (!calls.isEmpty()) {
            ArrayNode toolCalls = node.putArray("tool_calls");
            calls.forEach(call -> {
                ObjectNode entry = toolCalls.addObject().put("id", call.callId()).put("type", "function");
                entry.putObject("function")
                        .put("name", call.tool())
                        // O protocolo quer os argumentos como texto JSON, não como objeto.
                        .put("arguments", call.arguments() == null ? "{}" : call.arguments().toString());
            });
        }
    }
}
