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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
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

    private static final ObjectMapper json = new ObjectMapper();

    public static AutomationSpec parseToml(String text) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException("TOML inválido: " + toml.errors().getFirst());
        }
        try {
            return fromMap(json.readValue(toml.toJson(), new TypeReference<Map<String, Object>>() { }));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("TOML ilegível: " + e.getMessage());
        }
    }

    /** Do TOML convertido ou do JSON de uma proposta. Tudo o que não fecha vira erro com motivo. */
    @SuppressWarnings("unchecked")
    public static AutomationSpec fromMap(Map<String, Object> raw) {
        String id = text(raw, "id");
        if (id == null || !id.matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("id ausente ou inválido (a-z, 0-9, -, até 40)");
        }
        String name = text(raw, "name");
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
            Step parsed = step((Map<String, Object>) step, ids);
            ids.add(parsed.id());
            steps.add(parsed);
        }
        Map<String, Object> limits = raw.get("limits") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Limits parsedLimits = new Limits(duration(limits, "timeout", Limits.DEFAULT.timeout()),
                limits.get("maxFailuresBeforeDisable") instanceof Number number ? Math.max(1, number.intValue())
                        : Limits.DEFAULT.maxFailures());
        return new AutomationSpec(id, name.strip(), !Boolean.FALSE.equals(raw.get("enabled")),
                Boolean.TRUE.equals(raw.get("once")), trigger((Map<String, Object>) trigger), List.copyOf(steps),
                parsedLimits);
    }

    private static Trigger trigger(Map<String, Object> raw) {
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
        Duration every = duration(raw, "every", null);
        if (every == null || every.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("interval precisa de every ≥ 1 min (ex.: PT10M)");
        }
        return new Interval(every, catchUp);
    }

    private static Trigger schedule(Map<String, Object> raw, CatchUp catchUp) {
        String cron = text(raw, "cron");
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
        if (FORBIDDEN_EVENTS.contains(event) || Topic.MANDATORY.contains(event.topic())) {
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
        String metric = text(raw, "metric");
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
                duration(raw, "sustainedFor", Duration.ofSeconds(60)),
                duration(raw, "cooldown", Duration.ofMinutes(15)));
    }

    private static Step step(Map<String, Object> raw, List<String> previous) {
        String id = stepId(raw, previous);
        String when = text(raw, "when");
        if (when != null) {
            Expressions.checkWhen(when, previous);
        }
        String tool = stepTool(raw);
        String agent = text(raw, "agent");
        Notify notify = stepNotify(raw);
        if ((tool != null ? 1 : 0) + (agent != null ? 1 : 0) + (notify != null ? 1 : 0) != 1) {
            throw new IllegalArgumentException("o passo " + id + " precisa de exatamente um: tool, agent ou notify");
        }
        String task = text(raw, "task");
        if (agent != null && (task == null || task.isBlank())) {
            throw new IllegalArgumentException("o passo " + id + " de agente precisa de task");
        }
        Map<String, Object> args = stepArgs(raw);
        for (String template : templates(args, task, notify)) {
            Expressions.checkTemplate(template, previous);
        }
        return new Step(id, when, tool, args, agent, task, notify, retryAttempts(raw), retryBackoff(raw),
                onError(raw));
    }

    private static String stepId(Map<String, Object> raw, List<String> previous) {
        String id = text(raw, "id");
        if (id == null || !id.matches("[a-z0-9_]{1,20}") || previous.contains(id)
                || Set.of("event", "trigger").contains(id)) {
            throw new IllegalArgumentException("id de passo inválido ou repetido: " + id);
        }
        return id;
    }

    /** {@code skill:x} e {@code mcp:y} viram os nomes que o registro de ferramentas conhece. */
    private static String stepTool(Map<String, Object> raw) {
        String tool = text(raw, "tool");
        if (tool == null) {
            return null;
        }
        return tool.startsWith("skill:") ? tool.substring(6)
                : tool.startsWith("mcp:") ? "mcp." + tool.substring(4) : tool;
    }

    @SuppressWarnings("unchecked")
    private static Notify stepNotify(Map<String, Object> raw) {
        if (!(raw.get("notify") instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> given = (Map<String, Object>) map;
        String title = text(given, "title");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("notify precisa de title");
        }
        String severity = String.valueOf(given.getOrDefault("severity", "info")).toLowerCase(Locale.ROOT);
        if (!List.of("info", "warning", "high").contains(severity)) {
            throw new IllegalArgumentException("severity deve ser info, warning ou high");
        }
        return new Notify(title, String.valueOf(given.getOrDefault("body", "")), severity);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepArgs(Map<String, Object> raw) {
        return raw.get("args") instanceof Map<?, ?> map ? Map.copyOf((Map<String, Object>) map) : Map.of();
    }

    /** No máximo cinco tentativas: repetir sem teto transforma falha em tempestade. */
    @SuppressWarnings("unchecked")
    private static int retryAttempts(Map<String, Object> raw) {
        if (!(raw.get("retry") instanceof Map<?, ?> map)) {
            return 1;
        }
        return ((Map<String, Object>) map).get("attempts") instanceof Number number
                ? Math.max(1, Math.min(5, number.intValue())) : 1;
    }

    @SuppressWarnings("unchecked")
    private static Duration retryBackoff(Map<String, Object> raw) {
        Duration standard = Duration.ofSeconds(30);
        return raw.get("retry") instanceof Map<?, ?> map
                ? duration((Map<String, Object>) map, "backoff", standard) : standard;
    }

    private static String onError(Map<String, Object> raw) {
        String onError = String.valueOf(raw.getOrDefault("onError", "stop")).toLowerCase(Locale.ROOT);
        if (!List.of("stop", "skip", "notify").contains(onError)) {
            throw new IllegalArgumentException("onError deve ser stop, skip ou notify");
        }
        return onError;
    }

    private static List<String> templates(Map<String, Object> args, String task, Notify notify) {
        List<String> out = new ArrayList<>();
        collectTemplates(args, out);
        if (task != null) {
            out.add(task);
        }
        if (notify != null) {
            out.add(notify.title());
            out.add(notify.body());
        }
        return out;
    }

    private static void collectTemplates(Object value, List<String> out) {
        if (value instanceof String text) {
            out.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectTemplates(item, out));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectTemplates(item, out));
        }
    }

    private static CatchUp catchUp(Map<String, Object> raw) {
        try {
            return raw.get("catchUp") instanceof String given ? CatchUp.valueOf(given.toUpperCase(Locale.ROOT))
                    : CatchUp.SKIP;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("catchUp deve ser SKIP, ONCE ou ALL");
        }
    }

    private static String text(Map<String, Object> raw, String key) {
        return raw.get(key) instanceof String value ? value : null;
    }

    private static Duration duration(Map<String, Object> raw, String key, Duration fallback) {
        if (!(raw.get(key) instanceof String value)) {
            return fallback;
        }
        try {
            Duration parsed = Duration.parse(value);
            if (parsed.isNegative() || parsed.isZero()) {
                throw new IllegalArgumentException(key + " deve ser positiva");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " deve ser uma duração ISO-8601, como PT10M");
        }
    }

    /** O TOML que vai para {@code ~/.zordon/automations/<id>.toml}. */
    public String toToml() {
        StringBuilder out = new StringBuilder("# Automação criada pelo Zordon depois da sua aprovação (SPEC-025).\n")
                .append("# Para desligar, use a tela; o arquivo pode ficar.\n");
        out.append("id = ").append(quote(id)).append('\n');
        out.append("name = ").append(quote(name)).append('\n');
        out.append("enabled = ").append(enabled).append('\n');
        out.append("once = ").append(once).append("\n\n[trigger]\n");
        switch (trigger) {
            case Interval interval -> out.append("type = \"interval\"\nevery = ").append(quote(interval.every()
                    .toString())).append("\ncatchUp = \"").append(interval.catchUp()).append("\"\n");
            case Schedule schedule -> out.append("type = \"schedule\"\ncron = ").append(quote(schedule.cron()))
                    .append("\nzone = ").append(quote(schedule.zone().getId())).append("\ncatchUp = \"")
                    .append(schedule.catchUp()).append("\"\n");
            case OnEvent event -> out.append("type = \"event\"\nevent = \"").append(event.event()).append("\"\nmatch = ")
                    .append(inline(new LinkedHashMap<>(event.match()))).append('\n');
            case Condition condition -> out.append("type = \"condition\"\nmetric = ").append(quote(condition.metric()))
                    .append("\nabove = ").append(condition.above()).append("\nrearmBelow = ").append(condition.rearmBelow())
                    .append("\nsustainedFor = ").append(quote(condition.sustainedFor().toString()))
                    .append("\ncooldown = ").append(quote(condition.cooldown().toString())).append('\n');
        }
        for (Step step : steps) {
            out.append("\n[[step]]\nid = ").append(quote(step.id())).append('\n');
            if (step.when() != null) {
                out.append("when = ").append(quote(step.when())).append('\n');
            }
            switch (step.kind()) {
                case "tool" -> out.append("tool = ").append(quote(step.tool())).append("\nargs = ")
                        .append(inline(step.args())).append('\n');
                case "agent" -> out.append("agent = ").append(quote(step.agent())).append("\ntask = ")
                        .append(quote(step.task())).append('\n');
                default -> out.append("notify = ").append(inline(Map.of("title", step.message().title(), "body",
                        step.message().body(), "severity", step.message().severity()))).append('\n');
            }
            if (step.retryAttempts() > 1) {
                out.append("retry = { attempts = ").append(step.retryAttempts()).append(", backoff = ")
                        .append(quote(step.retryBackoff().toString())).append(" }\n");
            }
            if (!"stop".equals(step.onError())) {
                out.append("onError = ").append(quote(step.onError())).append('\n');
            }
        }
        out.append("\n[limits]\ntimeout = ").append(quote(limits.timeout().toString()))
                .append("\nmaxFailuresBeforeDisable = ").append(limits.maxFailures()).append('\n');
        return out.toString();
    }

    /** String básica do TOML: aspas, barra e controles escapados. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String inline(Map<String, ?> map) {
        StringBuilder out = new StringBuilder("{ ");
        boolean first = true;
        for (Map.Entry<String, ?> entry : new java.util.TreeMap<>(map).entrySet()) {
            out.append(first ? "" : ", ");
            first = false;
            out.append(entry.getKey().matches("[A-Za-z0-9_-]+") ? entry.getKey() : quote(entry.getKey()))
                    .append(" = ").append(value(entry.getValue()));
        }
        return out.append(first ? "}" : " }").toString();
    }

    @SuppressWarnings("unchecked")
    private static String value(Object value) {
        return switch (value) {
            case null -> "\"\"";
            case Boolean bool -> bool.toString();
            case Integer number -> number.toString();
            case Long number -> number.toString();
            case Number number -> Double.toString(number.doubleValue());
            case Map<?, ?> map -> inline((Map<String, ?>) map);
            case List<?> list -> "[" + String.join(", ", list.stream().map(AutomationSpec::value).toList()) + "]";
            default -> quote(String.valueOf(value));
        };
    }

    /** As ferramentas do workflow: o escopo aprovado da automação. */
    public Set<String> toolScope() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        steps.stream().filter(step -> step.tool() != null).forEach(step -> out.add(step.tool()));
        return Set.copyOf(out);
    }
}
