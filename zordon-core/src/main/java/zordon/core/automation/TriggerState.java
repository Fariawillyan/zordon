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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Estado de gatilho em memória; o último disparo é recuperado do banco. */
final class TriggerState {
    private Instant next;
    private long nextNanos;
    private boolean initialized;
    private boolean armed = true;
    private Long aboveSince;
    private Long lastCondition;

    List<Instant> due(AutomationSpec.Trigger trigger, Instant lastFired, Instant now, long nanos) {
        if (!(trigger instanceof AutomationSpec.Interval) && !(trigger instanceof AutomationSpec.Schedule)) {
            return List.of();
        }
        AutomationSpec.CatchUp catchUp = trigger instanceof AutomationSpec.Interval interval
                ? interval.catchUp() : ((AutomationSpec.Schedule) trigger).catchUp();
        if (!initialized) {
            initialized = true;
            next = advance(trigger, lastFired == null ? now : lastFired);
            List<Instant> missed = new ArrayList<>();
            if (lastFired != null && !next.isAfter(now)) {
                while (!next.isAfter(now) && missed.size() < 10) {
                    missed.add(next);
                    next = advance(trigger, next);
                }
                next = advance(trigger, now);
                if (catchUp == AutomationSpec.CatchUp.SKIP) {
                    missed.clear();
                } else if (catchUp == AutomationSpec.CatchUp.ONCE) {
                    missed = new ArrayList<>(List.of(now));
                }
            }
            nextNanos = nanos + Duration.between(now, next).toNanos();
            return missed;
        }
        boolean due = trigger instanceof AutomationSpec.Interval ? nanos - nextNanos >= 0 : !now.isBefore(next);
        if (!due) {
            return List.of();
        }
        next = advance(trigger, now);
        nextNanos = nanos + Duration.between(now, next).toNanos();
        return List.of(now);
    }

    boolean condition(AutomationSpec.Condition condition, double value, long nanos) {
        if (!Double.isFinite(value) || value < 0) {
            aboveSince = null;
            return false;
        }
        if (value < condition.rearmBelow()) {
            armed = true;
        }
        if (!armed || value <= condition.above()) {
            aboveSince = null;
            return false;
        }
        if (aboveSince == null) {
            aboveSince = nanos;
        }
        if (nanos - aboveSince < condition.sustainedFor().toNanos()
                || lastCondition != null && nanos - lastCondition < condition.cooldown().toNanos()) {
            return false;
        }
        armed = false;
        aboveSince = null;
        lastCondition = nanos;
        return true;
    }

    private static Instant advance(AutomationSpec.Trigger trigger, Instant after) {
        return trigger instanceof AutomationSpec.Interval interval ? after.plus(interval.every())
                : Cron.parse(((AutomationSpec.Schedule) trigger).cron())
                        .next(after.atZone(((AutomationSpec.Schedule) trigger).zone())).toInstant();
    }
}
