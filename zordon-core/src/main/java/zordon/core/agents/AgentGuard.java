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
import java.util.ArrayDeque;
import java.util.Map;
import java.util.function.LongSupplier;
import zordon.api.security.Decision;

/**
 * O disjuntor de uma execução (Agentes §5): o que contém um agente sequestrado por
 * injeção é o comportamento aparecer antes de o padrão se completar. Aberto, não
 * fecha sozinho.
 */
public final class AgentGuard {

    static final int DENIALS = 3;
    static final Duration WINDOW = Duration.ofSeconds(60);
    static final int IDENTICAL = 5;

    private final LongSupplier nanos;
    private final ArrayDeque<Long> denials = new ArrayDeque<>();
    private String lastCall;
    private int repeated;
    private volatile String tripped;

    public AgentGuard(LongSupplier nanos) {
        this.nanos = nanos;
    }

    /** Antes de executar: a mesma chamada cinco vezes seguidas é repetição improdutiva. */
    public synchronized void called(String tool, Map<String, Object> args) {
        String signature = tool + " " + new java.util.TreeMap<>(args);
        repeated = signature.equals(lastCall) ? repeated + 1 : 1;
        lastCall = signature;
        if (repeated >= IDENTICAL) {
            trip(IDENTICAL + " chamadas idênticas seguidas a " + tool);
        }
    }

    /** Depois do motor: negações seguidas, ou uma tentativa acima do teto. */
    public synchronized void decided(Decision decision) {
        if (!(decision instanceof Decision.Deny deny)) {
            return;
        }
        if (deny.reason().startsWith("acima do teto")) {
            trip("tentou uma ação " + deny.risk().wire() + ", " + deny.reason());
            return;
        }
        refused(deny.reason());
    }

    /** Uma recusa que não passou pelo motor (ferramenta fora do escopo). */
    public synchronized void refused(String reason) {
        long now = nanos.getAsLong();
        denials.addLast(now);
        while (!denials.isEmpty() && now - denials.peekFirst() > WINDOW.toNanos()) {
            denials.removeFirst();
        }
        if (denials.size() >= DENIALS) {
            trip(DENIALS + " ações negadas em " + WINDOW.toSeconds() + " s (a última: " + reason + ")");
        }
    }

    private void trip(String reason) {
        if (tripped == null) {
            tripped = reason;
        }
    }

    /** O motivo da suspensão, ou {@code null}. */
    public String tripped() {
        return tripped;
    }
}
