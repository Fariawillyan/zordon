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

import java.util.Arrays;
import java.util.Locale;

/** Família de protocolo de um provider — o que decide qual adaptador fala com ele. */
public enum ProviderType {
    /** API da Anthropic, com cache explícito e pensamento adaptativo. */
    ANTHROPIC("anthropic"),

    /** Qualquer servidor que fale Chat Completions: OpenAI, Ollama, LM Studio, OpenRouter… */
    OPENAI_COMPATIBLE("openai-compatible"),

    /** O {@code claude} CLI com a assinatura do usuário, sem ferramentas (ADR-0039, SPEC-018). */
    CLAUDE_CLI("claude-cli");

    private final String configName;

    ProviderType(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    static ProviderType parse(String value) {
        return Arrays.stream(values())
                .filter(type -> type.configName.equals(value == null ? "" : value.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "type desconhecido '" + value + "'; use \"anthropic\", \"openai-compatible\" ou \"claude-cli\""));
    }
}
