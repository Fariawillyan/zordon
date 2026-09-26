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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import zordon.ai.ModelRole;
import zordon.api.security.RiskLevel;

/** Converte a configuração TOML em um agente validado. */
final class AgentParser {

    static AgentProfile parse(String text, String source) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException("TOML inválido: " + toml.errors().getFirst().toString());
        }
        String id = id(toml);
        String prompt = prompt(toml);
        return new AgentProfile(id, value(toml, "name", id), value(toml, "description", ""), prompt.strip(),
                scope(toml), ceiling(toml), role(toml), budget(toml), source);
    }

    private static String value(TomlParseResult toml, String key, String fallback) {
        String value = toml.getString(key);
        return value == null ? fallback : value;
    }

    private static String id(TomlParseResult toml) {
        String id = toml.getString("id");
        if (id == null || !id.matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("id ausente ou inválido (a-z, 0-9, -, até 40)");
        }
        return id;
    }

    private static String prompt(TomlParseResult toml) {
        String prompt = toml.getString("prompt");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt ausente");
        }
        if (prompt.length() > 8_000) {
            throw new IllegalArgumentException("prompt maior que 8.000 caracteres");
        }
        return prompt;
    }

    private static ToolScope scope(TomlParseResult toml) {
        TomlTable tools = toml.getTable("tools");
        return tools == null ? ToolScope.ALL : new ToolScope(
                strings(tools.getArray("include"), List.of("*")),
                strings(tools.getArray("exclude"), List.of()),
                strings(tools.getArray("pinned"), List.of()));
    }

    private static RiskLevel ceiling(TomlParseResult toml) {
        String ceiling = toml.getString("permissions.ceiling");
        try {
            return ceiling == null ? RiskLevel.YELLOW : RiskLevel.valueOf(ceiling.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("permissions.ceiling deve ser GREEN, YELLOW ou RED");
        }
    }

    private static ModelRole role(TomlParseResult toml) {
        String role = toml.getString("model.role");
        try {
            return role == null ? ModelRole.CONVERSATION : ModelRole.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("model.role desconhecido: " + role);
        }
    }

    private static Budget budget(TomlParseResult toml) {
        Budget defaults = Budget.DEFAULT;
        try {
            Long steps = toml.getLong("budget.maxSteps");
            Long calls = toml.getLong("budget.maxToolCalls");
            Long tokens = toml.getLong("budget.maxTokens");
            String clock = toml.getString("budget.wallClock");
            return new Budget(steps == null ? defaults.maxSteps() : steps.intValue(),
                    calls == null ? defaults.maxToolCalls() : calls.intValue(),
                    tokens == null ? defaults.maxTokens() : tokens,
                    clock == null ? defaults.wallClock() : Duration.parse(clock));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("budget.wallClock deve ser uma duração ISO-8601, como PT5M");
        }
    }

    private static List<String> strings(TomlArray array, List<String> fallback) {
        if (array == null) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(array.getString(i));
        }
        return List.copyOf(out);
    }

    private AgentParser() {}
}
