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
package zordon.desktop.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

/** O rodapé de cada resposta: quem respondeu, quanto custou e se o número é medido. */
class TurnSummaryTest {

    @AcceptanceCriteria("SPEC-004/CA-8")
    @Test
    void consumoEstimadoApareceComoAproximado() {
        TurnSummary turn = TurnSummary.fromResponse(Map.of(
                        "done", true, "provider", "ollama", "model", "qwen2.5:7b",
                        "usage", Map.of("inputTokens", 120L, "outputTokens", 40L, "cacheReadTokens", 0L, "estimated", true),
                        "costUsd", "0"))
                .orElseThrow();

        assertThat(turn.footer()).isEqualTo("ollama · qwen2.5:7b · ≈120 tok entrada · ≈40 tok saída · 0 de cache · US$ 0.0000");
    }

    @AcceptanceCriteria("SPEC-004/CA-9")
    @Test
    void respostaDaReservaDizNoLugarDeQuem() {
        TurnSummary turn = TurnSummary.fromResponse(Map.of(
                        "done", true, "provider", "ollama", "model", "qwen2.5:7b",
                        "usage", Map.of("inputTokens", 10L, "outputTokens", 5L, "cacheReadTokens", 0L, "estimated", false),
                        "costUsd", "0", "fallbackFrom", "anthropic"))
                .orElseThrow();

        assertThat(turn.footer()).endsWith(" · reserva no lugar de anthropic");
    }

    @Test
    void respostaDaRotaLocalNaoTemCusto() {
        TurnSummary turn = TurnSummary.fromResponse(Map.of(
                        "done", true, "route", "fast:hora",
                        "usage", Map.of("inputTokens", 0L, "outputTokens", 0L, "cacheReadTokens", 0L, "estimated", false),
                        "costUsd", "0"))
                .orElseThrow();

        assertThat(turn.footer()).isEqualTo("resposta local · sem custo");
    }

    @Test
    void fragmentoNaoViraResumo() {
        assertThat(TurnSummary.fromResponse(Map.of("done", false, "delta", "Olá"))).isEmpty();
    }
}
