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
import java.util.List;
import java.util.Optional;
import zordon.ai.AiException;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.ContentBlock;
import zordon.ai.ModelRole;
import zordon.ai.Role;
import zordon.ai.registry.ProviderRegistry;

/**
 * Os pedidos a um modelo que não passam por um turno de conversa: planejar,
 * verificar, destilar e delegar. Todos escolhem o modelo do mesmo jeito e, quando
 * não precisam de ferramenta, mandam uma mensagem só.
 */
public final class RoleModels {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private RoleModels() {}

    /** O primeiro dos papéis com um provider pronto, na ordem dada. */
    public static Optional<ProviderRegistry.Selection> first(ProviderRegistry providers, ModelRole... roles) {
        for (ModelRole role : roles) {
            if (providers.select(role) instanceof ProviderRegistry.Resolution.Selected selected) {
                return Optional.of(selected.selection());
            }
        }
        return Optional.empty();
    }

    /** Um pedido de uma mensagem só, com o prompt de sistema e prazo de dois minutos. */
    public static AiResponse ask(ProviderRegistry.Selection selection, String system, String text, int maxTokens)
            throws AiException {
        return selection.provider().chat(AiRequest.builder(selection.choice().model())
                .systemPrompt(system)
                .messages(List.of(new AiMessage(Role.USER, List.of(new ContentBlock.Text(text)))))
                .maxOutputTokens(maxTokens)
                .timeout(TIMEOUT)
                .build());
    }
}
