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
package zordon.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

/** O rodapé de cada resposta: quem respondeu, quanto custou e se o número é medido. */
class ResponseFooterTest {

    @AcceptanceCriteria("SPEC-004/CA-8")
    @Test
    void consumoEstimadoApareceComoAproximado() {
        String footer = ZordonDesktop.footer(Map.of(
                "provider", "ollama",
                "model", "qwen2.5:7b",
                "usage", Map.of("inputTokens", 120L, "outputTokens", 40L, "cacheReadTokens", 0L, "estimated", true),
                "costUsd", "0"));

        assertThat(footer).isEqualTo("ollama · qwen2.5:7b · ≈120 tok entrada · ≈40 tok saída · 0 de cache · US$ 0.0000");
    }

    @AcceptanceCriteria("SPEC-004/CA-9")
    @Test
    void respostaDaReservaDizNoLugarDeQuem() {
        String footer = ZordonDesktop.footer(Map.of(
                "provider", "ollama",
                "model", "qwen2.5:7b",
                "usage", Map.of("inputTokens", 10L, "outputTokens", 5L, "cacheReadTokens", 0L, "estimated", false),
                "costUsd", "0",
                "fallbackFrom", "anthropic"));

        assertThat(footer).endsWith(" · reserva no lugar de anthropic");
    }

    @Test
    void consumoMedidoNaoLevaMarca() {
        String footer = ZordonDesktop.footer(Map.of(
                "provider", "anthropic",
                "model", "claude-opus-5",
                "usage", Map.of("inputTokens", 1240L, "outputTokens", 320L, "cacheReadTokens", 900L, "estimated", false),
                "costUsd", "0.0123"));

        assertThat(footer).doesNotContain("≈").contains("900 de cache").contains("US$ 0.0123");
    }
}
