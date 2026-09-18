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

import com.anthropic.models.messages.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.ContentBlock;
import zordon.ai.Pricing;
import zordon.ai.StopReason;
import zordon.api.TokenUsage;

/** Conversão de uma resposta não-streaming do SDK para o modelo do Zordon. */
final class MessageResponses {

    private MessageResponses() {}

    static AiResponse toResponse(Message message, AiRequest request, Pricing pricing, Duration latency) {
        List<ContentBlock> blocks = new ArrayList<>();
        message.content().forEach(block -> {
            block.thinking().ifPresent(thinking -> blocks.add(new ContentBlock.Thinking(thinking.thinking())));
            block.text().ifPresent(text -> blocks.add(new ContentBlock.Text(text.text())));
            block.toolUse().ifPresent(tool -> blocks.add(new ContentBlock.ToolUse(
                    tool.id(), tool.name(), new com.fasterxml.jackson.databind.ObjectMapper()
                            .valueToTree(tool._input()))));
        });

        var usage = new TokenUsage(
                message.usage().inputTokens(),
                message.usage().outputTokens(),
                message.usage().cacheCreationInputTokens().orElse(0L),
                message.usage().cacheReadInputTokens().orElse(0L));

        StopReason stopReason = message.stopReason().map(reason -> switch (reason.asString()) {
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS;
            case "refusal" -> StopReason.REFUSAL;
            default -> StopReason.END_TURN;
        }).orElse(StopReason.END_TURN);

        String refusal = message.stopDetails().flatMap(details -> details.explanation()).orElse(null);

        return new AiResponse(
                blocks, stopReason, usage, pricing.costOf(request.model(), usage),
                request.model(), latency, refusal, false);
    }
}
