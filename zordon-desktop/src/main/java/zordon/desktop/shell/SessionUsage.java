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

import java.math.BigDecimal;

/**
 * Consumo acumulado nesta sessão da janela.
 *
 * <p>A estimativa é contagiosa: se uma parcela foi estimada, o total é estimado
 * ([Uso de tokens §4](../../../../../../../docs/operations/token-usage.md#4-exato-vs-estimado)).
 */
public record SessionUsage(
        int turns, long inputTokens, long outputTokens, long cacheReadTokens, BigDecimal costUsd, boolean estimated) {

    public static final SessionUsage NONE = new SessionUsage(0, 0, 0, 0, BigDecimal.ZERO, false);

    public SessionUsage plus(TurnSummary turn) {
        return new SessionUsage(
                turns + 1,
                inputTokens + turn.inputTokens(),
                outputTokens + turn.outputTokens(),
                cacheReadTokens + turn.cacheReadTokens(),
                costUsd.add(turn.costUsd()),
                estimated || turn.estimated());
    }

    public String tokensLabel() {
        return (estimated ? "≈" : "") + (inputTokens + outputTokens) + " tokens";
    }
}
