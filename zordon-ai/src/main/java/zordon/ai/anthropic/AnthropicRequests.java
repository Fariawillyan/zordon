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

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.ContentBlock;
import zordon.ai.Effort;
import zordon.ai.Role;
import zordon.ai.Thinking;
import zordon.ai.ToolSpec;

/**
 * Tradução do {@link AiRequest} do Zordon para os parâmetros do SDK.
 *
 * <p>Separado do provider porque é aqui que moram os detalhes que geram bug
 * silencioso quando errados — cache no lugar certo, pensamento adaptativo,
 * esforço dentro de {@code output_config} — e eles merecem teste próprio.
 */
final class AnthropicRequests {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private AnthropicRequests() {}

    static MessageCreateParams toParams(AiRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxOutputTokens())
                .messages(request.messages().stream().map(AnthropicRequests::toMessage).toList());

        if (!request.systemPrompt().isBlank()) {
            builder.systemOfTextBlockParams(List.of(systemBlock(request)));
        }
        // Ordem de renderização da API: tools → system → messages. As ferramentas
        // vão ordenadas por nome porque o cache casa por prefixo, e duas
        // requisições equivalentes precisam gerar os mesmos bytes.
        request.tools().stream()
                .sorted(java.util.Comparator.comparing(ToolSpec::name))
                .map(AnthropicRequests::toTool)
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

    private static MessageParam toMessage(AiMessage message) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case ContentBlock.Text text ->
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build()));
                case ContentBlock.ToolUse call ->
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(call.callId())
                            .name(call.tool())
                            .input(toInput(call.arguments()))
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

    private static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        fields(spec.inputSchema().get("properties")).forEach(properties::putAdditionalProperty);

        List<String> required = new ArrayList<>();
        JsonNode declared = spec.inputSchema().get("required");
        if (declared != null && declared.isArray()) {
            declared.forEach(field -> required.add(field.asText()));
        }
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(required)
                        .build())
                .build();
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

    private static ToolUseBlockParam.Input toInput(JsonNode arguments) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        fields(arguments).forEach(input::putAdditionalProperty);
        return input.build();
    }

    /** Campos de um objeto JSON como valores do SDK; objeto ausente vira vazio. */
    private static Map<String, JsonValue> fields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, JsonValue> values = new java.util.LinkedHashMap<>();
        node.properties().forEach(entry ->
                values.put(entry.getKey(), JsonValue.from(MAPPER.convertValue(entry.getValue(), Object.class))));
        return values;
    }
}
