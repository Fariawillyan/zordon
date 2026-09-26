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
package zordon.core.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.ContentBlock;
import zordon.ai.Role;
import zordon.ai.ToolSpec;
import zordon.api.SessionId;

/** O que vai ao modelo: a conversa, a memória só na requisição e as ferramentas que cabem no pedido. */
final class TurnPrompt {

    private static final Logger log = LoggerFactory.getLogger(TurnManager.class);
    private static final int MAX_OUTPUT_TOKENS = 8_192;

    /** A conversa, já com a memória, e as ferramentas oferecidas pelas palavras do pedido. */
    record Prepared(List<AiMessage> messages, List<ToolSpec> offered) {}

    private final ConversationStore conversations;
    private final PromptComposer prompts;
    private final TurnHooks hooks;

    TurnPrompt(ConversationStore conversations, PromptComposer prompts, TurnHooks hooks) {
        this.conversations = conversations;
        this.prompts = prompts;
        this.hooks = hooks;
    }

    Prepared prepare(SessionId session, RunningTurn handle) {
        List<AiMessage> messages = prompts.toMessages(
                conversations.conversation(session, PromptComposer.MAX_HISTORY_MESSAGES));
        String userText = messages.isEmpty() ? "" : messages.getLast().text();
        messages = withMemory(messages, userText);
        ToolCaller toolCaller = hooks.caller();
        List<ToolSpec> offered = toolCaller == null || messages.isEmpty() ? List.of()
                : handle.scope == null ? toolCaller.offer(userText) : toolCaller.offer(userText, handle.scope);
        return new Prepared(messages, offered);
    }

    AiRequest request(TurnExchange ex, List<AiMessage> messages, List<ToolSpec> offered) {
        return AiRequest.builder(ex.selection().choice().model())
                .systemPrompt(ex.handle().scope == null ? prompts.systemPrompt()
                        : prompts.systemPrompt() + "\n\n" + ex.handle().scope.agent().prompt())
                .tools(offered)
                .messages(messages)
                .effort(ex.selection().choice().effortIfAny().orElse(null))
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    /**
     * A memória entra no começo da última mensagem do usuário, e só na requisição:
     * o prompt de sistema fica estável para o cache, e a conversa gravada fica limpa.
     */
    private List<AiMessage> withMemory(List<AiMessage> messages, String userText) {
        if (messages.isEmpty() || messages.getLast().role() != Role.USER) {
            return messages;
        }
        String block;
        try {
            block = hooks.recall().about(userText);
        } catch (RuntimeException e) {
            log.warn("memória indisponível neste turno: {}", e.getMessage());
            return messages;
        }
        if (block == null || block.isBlank()) {
            return messages;
        }
        List<AiMessage> out = new ArrayList<>(messages.subList(0, messages.size() - 1));
        out.add(new AiMessage(Role.USER, List.of(new ContentBlock.Text(block + "\n\n" + userText))));
        return out;
    }
}
