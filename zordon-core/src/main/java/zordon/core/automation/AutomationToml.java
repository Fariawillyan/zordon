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
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import zordon.core.automation.AutomationSpec.Condition;
import zordon.core.automation.AutomationSpec.Interval;
import zordon.core.automation.AutomationSpec.OnEvent;
import zordon.core.automation.AutomationSpec.Schedule;
import zordon.core.automation.AutomationSpec.Step;

/** A automação em TOML: o arquivo em {@code ~/.zordon/automations/} lido e escrito. */
final class AutomationToml {

    private static final ObjectMapper json = new ObjectMapper();

    private AutomationToml() {}

    static AutomationSpec parse(String text) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException("TOML inválido: " + toml.errors().getFirst());
        }
        try {
            return AutomationSpec.fromMap(json.readValue(toml.toJson(), new TypeReference<Map<String, Object>>() { }));
        } catch (IOException e) {
            throw new IllegalArgumentException("TOML ilegível: " + e.getMessage());
        }
    }

    /** O TOML que vai para {@code ~/.zordon/automations/<id>.toml}. */
    static String write(AutomationSpec spec) {
        StringBuilder out = new StringBuilder("# Automação criada pelo Zordon depois da sua aprovação (SPEC-025).\n")
                .append("# Para desligar, use a tela; o arquivo pode ficar.\n");
        out.append("id = ").append(quote(spec.id())).append('\n');
        out.append("name = ").append(quote(spec.name())).append('\n');
        out.append("enabled = ").append(spec.enabled()).append('\n');
        out.append("once = ").append(spec.once()).append("\n\n[trigger]\n");
        switch (spec.trigger()) {
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
        for (Step step : spec.steps()) {
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
        out.append("\n[limits]\ntimeout = ").append(quote(spec.limits().timeout().toString()))
                .append("\nmaxFailuresBeforeDisable = ").append(spec.limits().maxFailures()).append('\n');
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
        for (Map.Entry<String, ?> entry : new TreeMap<>(map).entrySet()) {
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
            case List<?> list -> "[" + String.join(", ", list.stream().map(AutomationToml::value).toList()) + "]";
            default -> quote(String.valueOf(value));
        };
    }
}
