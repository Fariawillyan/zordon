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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import zordon.ai.AiMessage;
import zordon.ai.Role;

/**
 * Monta o contexto que vai para o modelo.
 *
 * <p>A ordem importa porque o cache de prompt casa por <strong>prefixo</strong>:
 * identidade e regras primeiro, porque são estáveis; histórico e pedido por
 * último, porque mudam a cada turno.
 *
 * <p>Regra que parece detalhe e não é: <strong>nenhum carimbo de tempo no prompt
 * de sistema</strong>. É o erro clássico que zera o cache a cada requisição. A
 * hora, quando necessária, vem por outro caminho
 * ([Core §3](../../../../../docs/specs/core/design.md#3-composição-de-contexto)).
 */
public final class PromptComposer {

    /** Teto de histórico por turno (docs/specs/core/design.md §3). */
    public static final int MAX_HISTORY_MESSAGES = 20;

    private final String systemPrompt;

    public PromptComposer() {
        this(loadDefaultPrompt());
    }

    public PromptComposer(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /** Histórico em ordem cronológica, pronto para o provider. */
    public List<AiMessage> toMessages(List<StoredMessage> history) {
        return history.stream()
                .filter(message -> !message.text().isBlank())
                .map(message -> new AiMessage(
                        "user".equals(message.role()) ? Role.USER : Role.ASSISTANT,
                        List.of(new zordon.ai.ContentBlock.Text(message.text()))))
                .toList();
    }

    private static String loadDefaultPrompt() {
        try (var stream = PromptComposer.class.getResourceAsStream("/prompts/system.md")) {
            if (stream == null) {
                throw new IllegalStateException("prompt de sistema ausente do empacotamento");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao ler o prompt de sistema", e);
        }
    }
}
