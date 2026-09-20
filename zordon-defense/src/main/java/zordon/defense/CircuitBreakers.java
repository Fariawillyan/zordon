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
package zordon.defense;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O disjuntor por sujeito (Defesa §7). Abre por achado; **só o usuário** move de
 * aberto para supervisionado ou fechado — um disjuntor que fecha sozinho é o que o
 * atacante espera.
 */
@Spec("SPEC-027")
public final class CircuitBreakers {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Breaker(String subject, State state, Instant since, String reason, String findingId) {}

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakers.class);

    private final Clock clock;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakers(Clock clock) {
        this.clock = clock;
    }

    /** @return o estado anterior; {@code OPEN} se já estava aberto (nada a refazer) */
    public synchronized State open(String subject, String reason, String findingId) {
        Breaker current = breakers.get(subject);
        if (current != null && current.state() == State.OPEN) {
            return State.OPEN;
        }
        breakers.put(subject, new Breaker(subject, State.OPEN, clock.instant(), reason, findingId));
        log.warn("disjuntor aberto para {}: {}", subject, reason);
        return current == null ? State.CLOSED : current.state();
    }

    /** Liberação pelo usuário: {@code supervised} (HALF_OPEN) ou {@code closed}. */
    public synchronized Optional<State> release(String subject, String mode) {
        Breaker current = breakers.get(subject);
        if (current == null || current.state() == State.CLOSED) {
            return Optional.empty();
        }
        State to = "closed".equals(mode) ? State.CLOSED : State.HALF_OPEN;
        breakers.put(subject, new Breaker(subject, to, clock.instant(), "liberado pelo usuário (" + mode + ")",
                current.findingId()));
        log.info("disjuntor de {} → {}", subject, to);
        return Optional.of(to);
    }

    public boolean isOpen(String subject) {
        Breaker breaker = breakers.get(subject);
        return breaker != null && breaker.state() == State.OPEN;
    }

    /** Em prova: cada ação do sujeito pede confirmação na tela. */
    public boolean isSupervised(String subject) {
        Breaker breaker = breakers.get(subject);
        return breaker != null && breaker.state() == State.HALF_OPEN;
    }

    public List<Map<String, Object>> wire() {
        return breakers.values().stream().filter(breaker -> breaker.state() != State.CLOSED).map(breaker -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subject", breaker.subject());
            out.put("state", breaker.state().name().toLowerCase(java.util.Locale.ROOT));
            out.put("since", breaker.since().toString());
            out.put("reason", breaker.reason());
            if (breaker.findingId() != null) {
                out.put("findingId", breaker.findingId());
            }
            return out;
        }).toList();
    }

    public List<Breaker> open() {
        return breakers.values().stream().filter(breaker -> breaker.state() == State.OPEN).toList();
    }
}
