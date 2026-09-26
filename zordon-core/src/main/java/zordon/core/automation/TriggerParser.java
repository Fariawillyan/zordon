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
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.core.automation.AutomationSpec.CatchUp;
import zordon.core.automation.AutomationSpec.Condition;
import zordon.core.automation.AutomationSpec.Interval;
import zordon.core.automation.AutomationSpec.OnEvent;
import zordon.core.automation.AutomationSpec.Schedule;
import zordon.core.automation.AutomationSpec.Trigger;

/** O gatilho de uma automação, conferido: intervalo mínimo, cron válido, evento permitido e histerese. */
final class TriggerParser {

    private TriggerParser() {}

    static Trigger parse(Map<String, Object> raw) {
        String type = String.valueOf(raw.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
        CatchUp catchUp = catchUp(raw);
        return switch (type) {
            case "interval" -> interval(raw, catchUp);
            case "schedule" -> schedule(raw, catchUp);
            case "event" -> onEvent(raw);
            case "condition" -> condition(raw);
            default -> throw new IllegalArgumentException(
                    "trigger.type deve ser interval, schedule, event ou condition");
        };
    }

    private static Trigger interval(Map<String, Object> raw, CatchUp catchUp) {
        Duration every = SpecFields.duration(raw, "every", null);
        if (every == null || every.compareTo(AutomationSpec.MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("interval precisa de every ≥ 1 min (ex.: PT10M)");
        }
        return new Interval(every, catchUp);
    }

    private static Trigger schedule(Map<String, Object> raw, CatchUp catchUp) {
        String cron = SpecFields.text(raw, "cron");
        Cron.parse(cron);   // valida
        ZoneId zone;
        try {
            zone = raw.get("zone") instanceof String given ? ZoneId.of(given) : ZoneId.systemDefault();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("zone inválida: " + raw.get("zone"));
        }
        return new Schedule(cron, zone, catchUp);
    }

    /** Evento de segurança, de alteração ou de automação não dispara automação. */
    private static Trigger onEvent(Map<String, Object> raw) {
        EventType event;
        try {
            event = EventType.valueOf(String.valueOf(raw.get("event")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("event desconhecido: " + raw.get("event"));
        }
        if (AutomationSpec.FORBIDDEN_EVENTS.contains(event) || Topic.MANDATORY.contains(event.topic())) {
            throw new IllegalArgumentException("eventos de segurança, de alteração e de automação não"
                    + " disparam automação: " + event);
        }
        Map<String, String> match = new LinkedHashMap<>();
        if (raw.get("match") instanceof Map<?, ?> given) {
            given.forEach((key, value) -> match.put(String.valueOf(key), String.valueOf(value)));
        }
        return new OnEvent(event, Map.copyOf(match));
    }

    /** Histerese obrigatória: sem {@code rearmBelow} abaixo de {@code above}, a condição oscila. */
    private static Trigger condition(Map<String, Object> raw) {
        String metric = SpecFields.text(raw, "metric");
        if (!List.of("cpu", "mem", "disk", "load").contains(metric)) {
            throw new IllegalArgumentException("metric deve ser cpu, mem, disk ou load");
        }
        if (!(raw.get("above") instanceof Number above)) {
            throw new IllegalArgumentException("condition precisa de above");
        }
        double rearm = raw.get("rearmBelow") instanceof Number number ? number.doubleValue()
                : above.doubleValue() * 0.9;
        if (rearm >= above.doubleValue()) {
            throw new IllegalArgumentException("rearmBelow precisa ser menor que above (histerese)");
        }
        return new Condition(metric, above.doubleValue(), rearm,
                SpecFields.duration(raw, "sustainedFor", Duration.ofSeconds(60)),
                SpecFields.duration(raw, "cooldown", Duration.ofMinutes(15)));
    }

    private static CatchUp catchUp(Map<String, Object> raw) {
        try {
            return raw.get("catchUp") instanceof String given ? CatchUp.valueOf(given.toUpperCase(Locale.ROOT))
                    : CatchUp.SKIP;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("catchUp deve ser SKIP, ONCE ou ALL");
        }
    }
}
