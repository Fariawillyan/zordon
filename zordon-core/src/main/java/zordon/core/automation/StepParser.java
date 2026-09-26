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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zordon.core.automation.AutomationSpec.Notify;
import zordon.core.automation.AutomationSpec.Step;

/** Um passo de automação, conferido: exatamente um de ferramenta, agente ou aviso, e modelos válidos. */
final class StepParser {

    private StepParser() {}

    static Step parse(Map<String, Object> raw, List<String> previous) {
        String id = stepId(raw, previous);
        String when = SpecFields.text(raw, "when");
        if (when != null) {
            Expressions.checkWhen(when, previous);
        }
        String tool = stepTool(raw);
        String agent = SpecFields.text(raw, "agent");
        Notify notify = stepNotify(raw);
        if ((tool != null ? 1 : 0) + (agent != null ? 1 : 0) + (notify != null ? 1 : 0) != 1) {
            throw new IllegalArgumentException("o passo " + id + " precisa de exatamente um: tool, agent ou notify");
        }
        String task = SpecFields.text(raw, "task");
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
        String id = SpecFields.text(raw, "id");
        if (id == null || !id.matches("[a-z0-9_]{1,20}") || previous.contains(id)
                || Set.of("event", "trigger").contains(id)) {
            throw new IllegalArgumentException("id de passo inválido ou repetido: " + id);
        }
        return id;
    }

    /** {@code skill:x} e {@code mcp:y} viram os nomes que o registro de ferramentas conhece. */
    private static String stepTool(Map<String, Object> raw) {
        String tool = SpecFields.text(raw, "tool");
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
        String title = SpecFields.text(given, "title");
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
                ? SpecFields.duration((Map<String, Object>) map, "backoff", standard) : standard;
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
}
