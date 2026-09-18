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

import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import zordon.ai.AiStreamListener;
import zordon.ai.ContentBlock;
import zordon.ai.StopReason;
import zordon.api.TokenUsage;

/**
 * Monta a resposta a partir dos eventos brutos do streaming.
 *
 * <p>Uma classe à parte porque é lógica pura sobre eventos: dá para testá-la sem
 * rede, e é onde mora a tradução de {@code stop_reason} — inclusive a recusa, que
 * chega como sucesso HTTP e some se alguém a tratar como resposta vazia.
 */
final class StreamAccumulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiStreamListener listener;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<Long, PendingToolCall> toolCalls = new HashMap<>();
    private final List<ContentBlock.ToolUse> completedCalls = new ArrayList<>();

    private TokenUsage usage = TokenUsage.NONE;
    private StopReason stopReason = StopReason.END_TURN;
    private String refusalExplanation;

    StreamAccumulator(AiStreamListener listener) {
        this.listener = listener;
    }

    void accept(RawMessageStreamEvent event) {
        event.messageStart().ifPresent(this::onMessageStart);
        event.contentBlockStart().ifPresent(this::onBlockStart);
        event.contentBlockDelta().ifPresent(this::onDelta);
        event.contentBlockStop().ifPresent(stop -> finishToolCall(stop.index()));
        event.messageDelta().ifPresent(this::onMessageDelta);
    }

    private void onMessageStart(RawMessageStartEvent event) {
        var incoming = event.message().usage();
        usage = new TokenUsage(
                incoming.inputTokens(),
                incoming.outputTokens(),
                incoming.cacheCreationInputTokens().orElse(0L),
                incoming.cacheReadInputTokens().orElse(0L));
        listener.onUsage(usage);
    }

    private void onBlockStart(RawContentBlockStartEvent event) {
        event.contentBlock().toolUse().ifPresent(block ->
                toolCalls.put(event.index(), new PendingToolCall(block.id(), block.name(), new StringBuilder())));
    }

    private void onDelta(RawContentBlockDeltaEvent event) {
        event.delta().text().ifPresent(delta -> {
            text.append(delta.text());
            listener.onTextDelta(delta.text());
        });
        event.delta().thinking().ifPresent(delta -> {
            thinking.append(delta.thinking());
            listener.onThinking(delta.thinking());
        });
        event.delta().inputJson().ifPresent(delta ->
                Optional.ofNullable(toolCalls.get(event.index()))
                        .ifPresent(pending -> pending.json().append(delta.partialJson())));
    }

    private void onMessageDelta(RawMessageDeltaEvent event) {
        event.delta().stopReason().ifPresent(reason -> stopReason = translate(reason));
        event.delta().stopDetails().ifPresent(details ->
                refusalExplanation = details.explanation().orElse("o modelo recusou a requisição"));

        var delta = event.usage();
        usage = new TokenUsage(
                delta.inputTokens().orElse(usage.inputTokens()),
                delta.outputTokens(),
                delta.cacheCreationInputTokens().orElse(usage.cacheCreationTokens()),
                delta.cacheReadInputTokens().orElse(usage.cacheReadTokens()));
        listener.onUsage(usage);
    }

    private void finishToolCall(long index) {
        PendingToolCall pending = toolCalls.remove(index);
        if (pending == null) {
            return;
        }
        try {
            String json = pending.json().isEmpty() ? "{}" : pending.json().toString();
            ContentBlock.ToolUse call =
                    new ContentBlock.ToolUse(pending.id(), pending.name(), MAPPER.readTree(json));
            completedCalls.add(call);
            listener.onToolUse(call);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Argumento inválido volta ao modelo como erro de ferramenta; abortar o
            // turno inteiro por causa disto seria desproporcional.
            listener.onError(new zordon.ai.AiException(
                    zordon.ai.AiException.Kind.INVALID_REQUEST,
                    "argumentos inválidos para " + pending.name(), e));
        }
    }

    List<ContentBlock> content() {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!thinking.isEmpty()) {
            blocks.add(new ContentBlock.Thinking(thinking.toString()));
        }
        if (!text.isEmpty()) {
            blocks.add(new ContentBlock.Text(text.toString()));
        }
        blocks.addAll(completedCalls);
        return blocks;
    }

    TokenUsage usage() {
        return usage;
    }

    StopReason stopReason() {
        return stopReason;
    }

    String refusalExplanation() {
        return refusalExplanation;
    }

    void cancelled() {
        stopReason = StopReason.CANCELLED;
    }

    /**
     * {@code STOP_SEQUENCE} e {@code PAUSE_TURN} viram fim de turno: para o núcleo,
     * o que importa é se há o que executar a seguir.
     */
    private static StopReason translate(com.anthropic.models.messages.StopReason reason) {
        String value = reason.asString();
        return switch (value) {
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS;
            case "refusal" -> StopReason.REFUSAL;
            default -> StopReason.END_TURN;
        };
    }

    private record PendingToolCall(String id, String name, StringBuilder json) {}
}
