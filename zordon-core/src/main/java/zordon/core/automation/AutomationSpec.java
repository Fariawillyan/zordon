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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import zordon.api.event.EventType;
import zordon.api.trace.Spec;

/**
 * Uma automação (SPEC-025): gatilho, passos e limites. Declarativa de propósito: sem
 * laço, sem função, sem código. Onde precisa de julgamento, o passo é um agente.
 */
@Spec("SPEC-025")
public record AutomationSpec(String id, String name, boolean enabled, boolean once, Trigger trigger,
        List<Step> steps, Limits limits) {

    public enum CatchUp { SKIP, ONCE, ALL }

    public sealed interface Trigger {
        String summary();
    }

    public record Interval(Duration every, CatchUp catchUp) implements Trigger {
        @Override
        public String summary() {
            long minutes = every.toMinutes();
            return minutes % 60 == 0 && minutes >= 60 ? "a cada " + minutes / 60 + " h" : "a cada " + minutes + " min";
        }
    }

    public record Schedule(String cron, ZoneId zone, CatchUp catchUp) implements Trigger {
        @Override
        public String summary() {
            return "cron " + cron + " (" + zone + ")";
        }
    }

    public record OnEvent(EventType event, Map<String, String> match) implements Trigger {
        @Override
        public String summary() {
            return "quando " + event + (match.isEmpty() ? "" : " " + match);
        }
    }

    public record Condition(String metric, double above, double rearmBelow, Duration sustainedFor, Duration cooldown)
            implements Trigger {
        @Override
        public String summary() {
            return metric + " acima de " + above + " por " + sustainedFor.toSeconds() + " s";
        }
    }

    public record Notify(String title, String body, String severity) {}

    /**
     * Um passo: exatamente um de {@code tool}, {@code agent} ou {@code notify} (em {@code message}).
     *
     * @param onError {@code stop} (padrão, e avisa), {@code skip} ou {@code notify}
     */
    public record Step(String id, String when, String tool, Map<String, Object> args, String agent, String task,
            Notify message, int retryAttempts, Duration retryBackoff, String onError) {

        public String kind() {
            return tool != null ? "tool" : agent != null ? "agent" : "notify";
        }

        public String title() {
            return switch (kind()) {
                case "tool" -> "Ferramenta " + tool;
                case "agent" -> "Agente " + agent;
                default -> "Avisar: " + message.title();
            };
        }
    }

    public record Limits(Duration timeout, int maxFailures) {
        public static final Limits DEFAULT = new Limits(Duration.ofMinutes(5), 20);
    }

    /** Eventos que não disparam automação: segurança, alteração de projeto e as próprias automações. */
    static final Set<EventType> FORBIDDEN_EVENTS = Set.of(EventType.AUTOMATION_TRIGGERED, EventType.AUTOMATION_FINISHED);
    static final Duration MIN_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_STEPS = 10;

    public static AutomationSpec parseToml(String text) {
        return AutomationToml.parse(text);
    }

    /** Do TOML convertido ou do JSON de uma proposta. Tudo o que não fecha vira erro com motivo. */
    @SuppressWarnings("unchecked")
    public static AutomationSpec fromMap(Map<String, Object> raw) {
        String id = SpecFields.text(raw, "id");
        if (id == null || !id.matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("id ausente ou inválido (a-z, 0-9, -, até 40)");
        }
        String name = SpecFields.text(raw, "name");
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("name é obrigatório, até 120 caracteres");
        }
        if (!(raw.get("trigger") instanceof Map<?, ?> trigger)) {
            throw new IllegalArgumentException("trigger é obrigatório");
        }
        Object stepsRaw = raw.containsKey("step") ? raw.get("step") : raw.get("steps");
        if (!(stepsRaw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("a automação precisa de pelo menos um passo");
        }
        if (list.size() > MAX_STEPS) {
            throw new IllegalArgumentException("no máximo " + MAX_STEPS + " passos");
        }
        List<Step> steps = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> step)) {
                throw new IllegalArgumentException("passo inválido");
            }
            Step parsed = StepParser.parse((Map<String, Object>) step, ids);
            ids.add(parsed.id());
            steps.add(parsed);
        }
        Map<String, Object> limits = raw.get("limits") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Limits parsedLimits = new Limits(SpecFields.duration(limits, "timeout", Limits.DEFAULT.timeout()),
                limits.get("maxFailuresBeforeDisable") instanceof Number number ? Math.max(1, number.intValue())
                        : Limits.DEFAULT.maxFailures());
        return new AutomationSpec(id, name.strip(), !Boolean.FALSE.equals(raw.get("enabled")),
                Boolean.TRUE.equals(raw.get("once")), TriggerParser.parse((Map<String, Object>) trigger), List.copyOf(steps),
                parsedLimits);
    }

    /** O TOML que vai para {@code ~/.zordon/automations/<id>.toml}. */
    public String toToml() {
        return AutomationToml.write(this);
    }

    /** As ferramentas do workflow: o escopo aprovado da automação. */
    public Set<String> toolScope() {
        Set<String> out = new TreeSet<>();
        steps.stream().filter(step -> step.tool() != null).forEach(step -> out.add(step.tool()));
        return Set.copyOf(out);
    }
}
