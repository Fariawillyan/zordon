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

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import java.util.ArrayList;
import java.util.List;
import zordon.ai.AiMessage;
import zordon.ai.ContentBlock;
import zordon.ai.Role;

/** Uma mensagem do Zordon como parâmetro do SDK, bloco a bloco. */
final class AnthropicMessages {

    private AnthropicMessages() {}

    static MessageParam toMessage(AiMessage message) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case ContentBlock.Text text ->
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build()));
                case ContentBlock.ToolUse call ->
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(call.callId())
                            .name(call.tool())
                            .input(AnthropicTools.toInput(call.arguments()))
                            .build()));
                case ContentBlock.ToolResult result ->
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(result.callId())
                            .content(result.content())
                            .isError(result.isError())
                            .build()));
                // O pensamento não volta para o modelo: ele é resumo para o usuário.
                case ContentBlock.Thinking ignored -> { }
            }
        }
        return MessageParam.builder()
                .role(message.role() == Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }
}
