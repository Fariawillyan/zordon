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
package zordon.api;

/**
 * Consumo de tokens de uma requisição ao provider.
 *
 * <p>{@code cacheReadTokens} existe separado por um motivo operacional: se ele vier
 * zero em requisições repetidas, o cache de prompt está sendo invalidado por algum
 * byte instável no prefixo — o maior risco do marco M1 (docs/roadmap.md).
 */
public record TokenUsage(
        long inputTokens, long outputTokens, long cacheCreationTokens, long cacheReadTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || cacheCreationTokens < 0 || cacheReadTokens < 0) {
            throw new IllegalArgumentException("contagem de tokens não pode ser negativa");
        }
    }

    public long totalTokens() {
        return inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheCreationTokens + other.cacheCreationTokens,
                cacheReadTokens + other.cacheReadTokens);
    }
}
