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

import java.util.List;
import java.util.Objects;

/** Uma mensagem da conversa enviada ao provider. */
public record AiMessage(Role role, List<ContentBlock> content) {

    public AiMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
    }

    public static AiMessage user(String text) {
        return new AiMessage(Role.USER, List.of(new ContentBlock.Text(text)));
    }

    public static AiMessage assistant(String text) {
        return new AiMessage(Role.ASSISTANT, List.of(new ContentBlock.Text(text)));
    }

    /** O texto concatenado dos blocos textuais — o que se mostra e o que se guarda. */
    public String text() {
        return content.stream()
                .filter(ContentBlock.Text.class::isInstance)
                .map(block -> ((ContentBlock.Text) block).text())
                .reduce("", String::concat);
    }
}
