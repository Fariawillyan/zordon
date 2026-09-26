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

import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.ContentBlock;

/** Este protocolo não tem contagem sem geração; o que existe é estimativa, declarada como tal. */
final class TokenEstimate {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimate() {}

    static long input(AiRequest request) {
        long chars = request.systemPrompt().length();
        for (AiMessage message : request.messages()) {
            for (ContentBlock block : message.content()) {
                chars += switch (block) {
                    case ContentBlock.Text part -> part.text().length();
                    case ContentBlock.ToolResult part -> part.content().length();
                    case ContentBlock.ToolUse part -> part.arguments() == null ? 0 : part.arguments().toString().length();
                    case ContentBlock.Thinking part -> 0;
                };
            }
        }
        return tokens(chars);
    }

    static long tokens(String text) {
        return tokens(text.length());
    }

    private static long tokens(long chars) {
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }
}
