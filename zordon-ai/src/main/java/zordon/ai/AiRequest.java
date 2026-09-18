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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Uma requisição ao modelo.
 *
 * <p>A ordem dos campos reflete a ordem de renderização da API — ferramentas,
 * sistema, mensagens — porque o cache de prompt casa por <strong>prefixo</strong>:
 * qualquer byte que mude invalida tudo depois dele
 * ([Core §3](../../../../docs/specs/core/design.md#3-composição-de-contexto)).
 */
public record AiRequest(
        String model,
        String systemPrompt,
        List<ToolSpec> tools,
        List<AiMessage> messages,
        Effort effort,
        Thinking thinking,
        int maxOutputTokens,
        boolean cacheSystemPrompt,
        Duration timeout) {

    public AiRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        // effort pode faltar: significa "o padrão do provider". Mandar esforço a um
        // modelo que não raciocina é erro 400 em alguns protocolos (ADR-0026).
        Objects.requireNonNull(thinking, "thinking");
        Objects.requireNonNull(timeout, "timeout");
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("requisição sem mensagem nenhuma");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens precisa ser positivo");
        }
    }

    /** O esforço pedido, se algum. Ausente: cada provider usa o próprio padrão. */
    public java.util.Optional<Effort> effortIfAny() {
        return java.util.Optional.ofNullable(effort);
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    /** Construtor fluente: o record tem nove componentes e a chamada posicional seria ilegível. */
    public static final class Builder {

        private final String model;
        private String systemPrompt = "";
        private List<ToolSpec> tools = List.of();
        private List<AiMessage> messages = List.of();
        private Effort effort;
        private Thinking thinking = Thinking.ADAPTIVE;
        private int maxOutputTokens = 8_192;
        private boolean cacheSystemPrompt = true;
        private Duration timeout = Duration.ofMinutes(5);

        private Builder(String model) {
            this.model = model;
        }

        public Builder systemPrompt(String value) {
            this.systemPrompt = value;
            return this;
        }

        public Builder tools(List<ToolSpec> value) {
            this.tools = value;
            return this;
        }

        public Builder messages(List<AiMessage> value) {
            this.messages = value;
            return this;
        }

        public Builder effort(Effort value) {
            this.effort = value;
            return this;
        }

        public Builder thinking(Thinking value) {
            this.thinking = value;
            return this;
        }

        public Builder maxOutputTokens(int value) {
            this.maxOutputTokens = value;
            return this;
        }

        public Builder cacheSystemPrompt(boolean value) {
            this.cacheSystemPrompt = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public AiRequest build() {
            return new AiRequest(
                    model, systemPrompt, tools, messages, effort, thinking,
                    maxOutputTokens, cacheSystemPrompt, timeout);
        }
    }
}
