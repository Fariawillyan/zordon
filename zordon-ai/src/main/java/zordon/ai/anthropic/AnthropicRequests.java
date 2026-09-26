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

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import java.util.List;
import zordon.ai.AiRequest;
import zordon.ai.Effort;
import zordon.ai.Thinking;
import zordon.ai.ToolSpec;

/**
 * Tradução do {@link AiRequest} do Zordon para os parâmetros do SDK.
 *
 * <p>Separado do provider porque é aqui que moram os detalhes que geram bug
 * silencioso quando errados — cache no lugar certo, pensamento adaptativo,
 * esforço dentro de {@code output_config} — e eles merecem teste próprio.
 * Mensagens ficam em {@link AnthropicMessages}; ferramentas, em {@link AnthropicTools}.
 */
final class AnthropicRequests {

    private AnthropicRequests() {}

    static MessageCreateParams toParams(AiRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxOutputTokens())
                .messages(request.messages().stream().map(AnthropicMessages::toMessage).toList());

        if (!request.systemPrompt().isBlank()) {
            builder.systemOfTextBlockParams(List.of(systemBlock(request)));
        }
        // Ordem de renderização da API: tools → system → messages. As ferramentas
        // vão ordenadas por nome porque o cache casa por prefixo, e duas
        // requisições equivalentes precisam gerar os mesmos bytes.
        request.tools().stream()
                .sorted(java.util.Comparator.comparing(ToolSpec::name))
                .map(AnthropicTools::toTool)
                .forEach(builder::addTool);

        if (request.thinking() == Thinking.ADAPTIVE) {
            // O padrão destes modelos é omitir o resumo, e sem ele a interface
            // parece travada durante uma pausa longa de raciocínio.
            builder.thinking(ThinkingConfigAdaptive.builder()
                    .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                    .build());
        } else {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }

        // Esforço ausente: a API aplica o padrão dela, em vez de receber um valor que
        // o usuário não escolheu.
        request.effortIfAny().ifPresent(effort ->
                builder.outputConfig(OutputConfig.builder().effort(toEffort(effort)).build()));
        return builder.build();
    }

    /**
     * O prompt de sistema é o bloco estável do prefixo: marcá-lo é o ganho de custo
     * mais barato que existe. Nada de timestamp aqui — é o invalidador clássico.
     */
    private static TextBlockParam systemBlock(AiRequest request) {
        TextBlockParam.Builder block = TextBlockParam.builder().text(request.systemPrompt());
        if (request.cacheSystemPrompt()) {
            block.cacheControl(CacheControlEphemeral.builder().build());
        }
        return block.build();
    }

    private static OutputConfig.Effort toEffort(Effort effort) {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
            case XHIGH -> OutputConfig.Effort.XHIGH;
            case MAX -> OutputConfig.Effort.MAX;
        };
    }
}
