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
package zordon.core.usage;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.Spec;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.memory.UsageStore;

/**
 * Quanto o dia custou (SPEC-029). Soma o que cada resposta já informa: sem
 * estimativa inventada, sem telemetria, e nada sai da máquina.
 */
@Spec("SPEC-029")
public final class UsageTracker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UsageTracker.class);

    private final UsageStore store;
    private final ZordonEventBus bus;
    private final Clock clock;
    private zordon.core.event.EventSubscription subscription;

    public UsageTracker(UsageStore store, ZordonEventBus bus, Clock clock) {
        this.store = store;
        this.bus = bus;
        this.clock = clock;
    }

    public void start() {
        subscription = bus.subscribe("usage", Set.of(Topic.CHAT, Topic.AGENTS), QueuePolicy.dropOldest(512),
                this::accept);
    }

    /** Um evento do barramento. Público para o teste alimentar o contador sem barramento. */
    public void accept(EventEnvelope event) {
        try {
            if (event.type() == EventType.AI_RESPONSE && Boolean.TRUE.equals(event.payload().get("done"))
                    && event.payload().get("usage") instanceof Map<?, ?> usage) {
                record(String.valueOf(event.payload().getOrDefault("provider", "?")),
                        String.valueOf(event.payload().getOrDefault("model", "?")), "turno",
                        number(usage.get("inputTokens")), number(usage.get("outputTokens")));
            } else if (event.type() == EventType.AGENT_FINISHED
                    && event.payload().get("usage") instanceof Map<?, ?> usage) {
                long tokens = number(usage.get("tokens"));
                if (tokens > 0) {
                    record("?", "?", "agente:" + event.payload().getOrDefault("agent", "?"), tokens, 0);
                }
            }
        } catch (RuntimeException e) {
            log.debug("uso não contabilizado: {}", e.getMessage());
        }
    }

    private void record(String provider, String model, String actor, long input, long output) {
        if (input <= 0 && output <= 0) {
            return;
        }
        store.recordUsage(LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault()), provider, model, actor,
                input, output);
    }

    private static long number(Object value) {
        return value instanceof Number found ? found.longValue() : 0;
    }

    public Map<String, Object> summary(int days) {
        return store.usageSummary(days <= 0 ? 7 : Math.min(days, 90));
    }

    @Override
    public void close() {
        if (subscription != null) {
            bus.unsubscribe(subscription);
        }
    }
}
