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
package zordon.ai.registry;

import java.util.Map;
import zordon.ai.AiProvider;
import zordon.ai.Pricing;
import zordon.ai.anthropic.AnthropicProvider;
import zordon.ai.cli.ClaudeCliProvider;
import zordon.ai.cli.CliRunner;
import zordon.ai.openai.OpenAiCompatibleProvider;

/** Cria o adaptador de cada provider configurado, resolvendo a chave no ambiente. */
final class ProviderFactory {

    private ProviderFactory() {}

    static AiProvider create(ProviderConfig config, Pricing pricing, Map<String, String> environment,
            CliRunner cli) {
        return switch (config.type()) {
            case ANTHROPIC -> {
                SecretRef reference = config.apiKeyIfAny().orElse(new SecretRef("ANTHROPIC_API_KEY"));
                yield AnthropicProvider.create(requireKey(reference, environment), config.baseUrlIfAny(), pricing);
            }
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider(
                    config.id(),
                    config.baseUrl(),
                    // Chave é opcional aqui: um Ollama local não pede nenhuma.
                    config.apiKeyIfAny().map(reference -> requireKey(reference, environment)).orElse(null),
                    config.maxTokensParam(),
                    pricing);
            case CLAUDE_CLI -> {
                if (cli == null) {
                    throw new IllegalStateException("não há como rodar o claude neste núcleo");
                }
                if (!cli.available("claude")) {
                    throw new IllegalStateException("claude não está no catálogo de programas; instale o claude CLI"
                            + " (npm i -g @anthropic-ai/claude-code), entre com `claude` e reinicie o núcleo");
                }
                yield new ClaudeCliProvider(config.id(), cli);
            }
        };
    }

    private static String requireKey(SecretRef reference, Map<String, String> environment) {
        return reference.resolve(environment).orElseThrow(() -> new IllegalStateException(
                "defina " + reference.variable() + " (em ~/.zordon/secrets.env para o serviço)"));
    }
}
