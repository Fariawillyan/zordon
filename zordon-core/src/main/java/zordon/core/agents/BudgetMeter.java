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

import java.util.function.LongSupplier;

/**
 * O que uma execução já gastou. Um só medidor para o pai e os filhos: a delegação
 * gasta do orçamento de quem delegou (Agentes §4, regra 2).
 */
public final class BudgetMeter {

    private final Budget budget;
    private final LongSupplier nanos;
    private final long deadline;
    private int steps;
    private int calls;
    private long tokens;

    public BudgetMeter(Budget budget, LongSupplier nanos) {
        this.budget = budget;
        this.nanos = nanos;
        this.deadline = nanos.getAsLong() + budget.wallClock().toNanos();
    }

    /** Uma volta ao modelo. @return o motivo de parar, ou {@code null} */
    public synchronized String step() {
        String over = exceeded();
        if (over != null) {
            return over;
        }
        if (steps + 1 > budget.maxSteps()) {
            return "passos";
        }
        steps++;
        return null;
    }

    /** Chamadas de ferramenta de uma volta. @return o motivo de parar, ou {@code null} */
    public synchronized String calls(int count) {
        if (calls + count > budget.maxToolCalls()) {
            return "chamadas";
        }
        calls += count;
        return null;
    }

    public synchronized void tokens(long used) {
        tokens += Math.max(0, used);
    }

    /** Tokens ou tempo estourados. */
    public synchronized String exceeded() {
        if (tokens > budget.maxTokens()) {
            return "tokens";
        }
        if (nanos.getAsLong() - deadline > 0) {
            return "tempo";
        }
        return null;
    }

    public synchronized int steps() {
        return steps;
    }

    public synchronized int calls() {
        return calls;
    }

    public synchronized long tokens() {
        return tokens;
    }

    public Budget budget() {
        return budget;
    }
}
