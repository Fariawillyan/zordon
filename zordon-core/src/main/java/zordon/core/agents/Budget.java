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
package zordon.core.agents;

import java.time.Duration;

/**
 * O orçamento de uma execução (Interfaces §7). Custo em dinheiro fica de fora:
 * pela assinatura, o que acaba é a cota, e ela se mede em tokens.
 */
public record Budget(int maxSteps, int maxToolCalls, long maxTokens, Duration wallClock) {

    /** Os tetos da SPEC-019, para o agente que não declara os seus. */
    public static final Budget DEFAULT = new Budget(15, 25, 200_000, Duration.ofMinutes(10));

    public Budget {
        if (maxSteps < 1 || maxToolCalls < 0 || maxTokens < 1 || wallClock == null || wallClock.isNegative()
                || wallClock.isZero()) {
            throw new IllegalArgumentException("orçamento inválido");
        }
        maxSteps = Math.min(maxSteps, 50);
        maxToolCalls = Math.min(maxToolCalls, 100);
    }
}
