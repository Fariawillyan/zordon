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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import zordon.ai.AiException;
import zordon.ai.AiStreamListener;
import zordon.ai.ContentBlock;
import zordon.ai.StopReason;
import zordon.api.TokenUsage;

/**
 * Monta a resposta a partir dos pedaços ({@code chunks}) do streaming.
 *
 * <p>Lógica pura sobre JSON, testável sem rede. Aceita as variações conhecidas do
 * protocolo: raciocínio em {@code reasoning_content} (DeepSeek) ou {@code reasoning}
 * (Ollama e outros), recusa em {@code refusal} (OpenAI).
 */
final class ChatCompletionsAccumulator {

    private final AiStreamListener listener;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder refusal = new StringBuilder();
    private final Map<Integer, PendingCall> calls = new TreeMap<>();

    private TokenUsage usage;
    private StopReason stopReason = StopReason.END_TURN;
    private boolean receivedAnything;

    ChatCompletionsAccumulator(AiStreamListener listener) {
        this.listener = listener;
    }

    void accept(JsonNode chunk) {
        receivedAnything = true;
        JsonNode reportedUsage = chunk.get("usage");
        if (reportedUsage != null && reportedUsage.isObject()) {
            usage = toUsage(reportedUsage);
            listener.onUsage(usage);
        }
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");
        if (delta != null) {
            onDelta(delta);
        }
        JsonNode finish = choice.get("finish_reason");
        if (finish != null && finish.isTextual()) {
            stopReason = translate(finish.asText());
        }
    }

    private void onDelta(JsonNode delta) {
        textOf(delta, "content").ifPresent(part -> {
            text.append(part);
            listener.onTextDelta(part);
        });
        textOf(delta, "reasoning_content").or(() -> textOf(delta, "reasoning")).ifPresent(part -> {
            reasoning.append(part);
            listener.onThinking(part);
        });
        textOf(delta, "refusal").ifPresent(refusal::append);

        JsonNode toolCalls = delta.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
            toolCalls.forEach(this::onToolCallDelta);
        }
    }

    /** O primeiro pedaço traz id e nome; os seguintes só acrescentam argumentos. */
    private void onToolCallDelta(JsonNode delta) {
        int index = delta.path("index").asInt(calls.size());
        PendingCall pending = calls.computeIfAbsent(index, key -> new PendingCall());
        textOf(delta, "id").ifPresent(id -> pending.id = id);
        JsonNode function = delta.get("function");
        if (function != null) {
            textOf(function, "name").ifPresent(name -> pending.name = name);
            textOf(function, "arguments").ifPresent(pending.arguments::append);
        }
    }

    List<ContentBlock> content() {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!reasoning.isEmpty()) {
            blocks.add(new ContentBlock.Thinking(reasoning.toString()));
        }
        if (!text.isEmpty()) {
            blocks.add(new ContentBlock.Text(text.toString()));
        }
        calls.values().forEach(pending -> toToolUse(pending).ifPresent(call -> {
            blocks.add(call);
            listener.onToolUse(call);
        }));
        return blocks;
    }

    String text() {
        return text.toString();
    }

    /** Recusa do modelo, quando houve — é ela que vira o motivo exibido ao usuário. */
    String refusalExplanation() {
        return refusal.isEmpty() ? null : refusal.toString();
    }

    StopReason stopReason() {
        return refusal.isEmpty() ? stopReason : StopReason.REFUSAL;
    }

    java.util.Optional<TokenUsage> reportedUsage() {
        return java.util.Optional.ofNullable(usage);
    }

    boolean receivedAnything() {
        return receivedAnything;
    }

    boolean producedText() {
        return !text.isEmpty();
    }

    void cancelled() {
        stopReason = StopReason.CANCELLED;
    }

    private java.util.Optional<ContentBlock.ToolUse> toToolUse(PendingCall pending) {
        if (pending.id == null || pending.name == null) {
            return java.util.Optional.empty();
        }
        try {
            String arguments = pending.arguments.isEmpty() ? "{}" : pending.arguments.toString();
            return java.util.Optional.of(new ContentBlock.ToolUse(
                    pending.id, pending.name, ChatCompletionsRequests.MAPPER.readTree(arguments)));
        } catch (JsonProcessingException e) {
            // Argumento inválido volta ao modelo como erro de ferramenta; não é
            // motivo para abortar o turno inteiro.
            listener.onError(new AiException(
                    AiException.Kind.INVALID_REQUEST, "argumentos inválidos para " + pending.name, e));
            return java.util.Optional.empty();
        }
    }

    /**
     * Neste protocolo, {@code prompt_tokens} inclui os tokens lidos do cache; no do
     * Zordon, entrada e cache são contados à parte. Subtrair é o que faz o custo
     * bater com a tabela de preços, que cobra os dois de forma diferente.
     */
    private static TokenUsage toUsage(JsonNode node) {
        long prompt = node.path("prompt_tokens").asLong(0);
        long cached = node.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        long completion = node.path("completion_tokens").asLong(0);
        return new TokenUsage(Math.max(0, prompt - cached), completion, 0, cached);
    }

    private static StopReason translate(String finishReason) {
        return switch (finishReason) {
            case "length" -> StopReason.MAX_TOKENS;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "content_filter" -> StopReason.REFUSAL;
            default -> StopReason.END_TURN;
        };
    }

    private static java.util.Optional<String> textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isEmpty()
                ? java.util.Optional.of(value.asText())
                : java.util.Optional.empty();
    }

    private static final class PendingCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
