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

import java.util.List;
import java.util.regex.Pattern;

/**
 * Quais ferramentas um agente vê. Aceita {@code *} e os prefixos da documentação
 * ({@code skill:dev.*}, {@code mcp:git.*}).
 */
public record ToolScope(List<String> include, List<String> exclude, List<String> pinned) {

    public static final ToolScope ALL = new ToolScope(List.of("*"), List.of(), List.of());

    public ToolScope {
        include = include.stream().map(ToolScope::plain).toList();
        exclude = exclude.stream().map(ToolScope::plain).toList();
        pinned = pinned.stream().map(ToolScope::plain).toList();
    }

    public boolean allows(String tool) {
        return include.stream().anyMatch(pattern -> matches(pattern, tool))
                && exclude.stream().noneMatch(pattern -> matches(pattern, tool));
    }

    static String plain(String pattern) {
        String out = pattern.strip();
        if (out.startsWith("skill:")) {
            out = out.substring("skill:".length());
        } else if (out.startsWith("mcp:")) {
            out = "mcp." + out.substring("mcp:".length());
        }
        return out;
    }

    static boolean matches(String pattern, String tool) {
        String regex = java.util.Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote)
                .reduce((a, b) -> a + ".*" + b).orElse("");
        return tool.matches(regex);
    }
}
