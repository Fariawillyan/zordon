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
package zordon.core.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import zordon.api.event.EventEnvelope;
import zordon.api.event.Topic;
import zordon.core.monitor.SystemSampler;

/** O que dispara: horário, intervalo, condição sobre as métricas e eventos. Sob o lock do {@link AutomationEngine}. */
final class AutomationTriggers {

    private final AutomationCatalog catalog;
    private final AutomationRuns runs;
    private final Supplier<SystemSampler.Snapshot> metrics;
    private final Clock clock;
    private final LongSupplier nanos;

    AutomationTriggers(AutomationCatalog catalog, AutomationRuns runs, Supplier<SystemSampler.Snapshot> metrics,
            Clock clock, LongSupplier nanos) {
        this.catalog = catalog;
        this.runs = runs;
        this.metrics = metrics;
        this.clock = clock;
        this.nanos = nanos;
    }

    void tick() {
        Instant now = clock.instant();
        long monotonic = nanos.getAsLong();
        SystemSampler.Snapshot sample = metrics.get();
        for (AutomationSpec spec : catalog.specs()) {
            if (!catalog.enabled(spec)) {
                continue;
            }
            TriggerState trigger = catalog.trigger(spec.id());
            if (spec.trigger() instanceof AutomationSpec.Condition condition) {
                if (fresh(sample.at(), now)
                        && trigger.condition(condition, sample.metric(condition.metric()), monotonic)) {
                    runs.launch(spec, List.of(Map.of("metric", condition.metric(), "value", sample.metric(condition.metric()))), null);
                }
            } else {
                List<Instant> due = trigger.due(spec.trigger(), catalog.lastFiredAt(spec.id()), now, monotonic);
                if (!due.isEmpty()) {
                    runs.launch(spec, due.stream().map(at -> Map.<String, Object>of("scheduledAt", at.toString())).toList(), null);
                }
            }
        }
    }

    void event(EventEnvelope event) {
        if (Topic.MANDATORY.contains(event.type().topic())) {
            return;
        }
        for (AutomationSpec spec : catalog.specs()) {
            if (catalog.enabled(spec) && spec.trigger() instanceof AutomationSpec.OnEvent trigger
                    && trigger.event() == event.type() && trigger.match().entrySet().stream()
                            .allMatch(entry -> entry.getValue().equals(String.valueOf(event.payload().get(entry.getKey()))))) {
                runs.launch(spec, List.of(event.payload()), null);
            }
        }
    }

    /** Uma amostra velha ou do futuro não decide condição nenhuma. */
    private static boolean fresh(Instant sample, Instant now) {
        return !sample.equals(Instant.EPOCH) && !sample.isAfter(now) && Duration.between(sample, now).getSeconds() <= 10;
    }
}
