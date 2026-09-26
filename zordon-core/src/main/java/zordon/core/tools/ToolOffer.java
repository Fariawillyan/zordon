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
package zordon.core.tools;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import zordon.api.security.RiskLevel;

/**
 * As ferramentas para o modelo (SPEC-019 CA-3): as fixas e as que casam com as
 * palavras do pedido, no máximo {@link SkillRuntime#MAX_OFFERED}.
 */
final class ToolOffer {

    private ToolOffer() {}

    /** Só o que {@code allows} permite e o que cabe no teto; {@code pinned} vazio usa as fixas padrão. */
    static List<SkillRuntime.Offered> offer(Map<String, Tool> tools, String userText, Predicate<String> allows,
            List<String> pinned, RiskLevel ceiling) {
        Predicate<Tool> fits = tool -> tool.modelVisible() && allows.test(tool.name())
                && (ceiling == null || tool.baseRisk().compareTo(ceiling) <= 0);
        String text = userText == null ? "" : Normalizer.normalize(userText.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        Set<String> words = new HashSet<>(List.of(text.split("[^a-z0-9]+")));
        words.removeIf(word -> word.length() < 4);
        List<Tool> chosen = new ArrayList<>();
        (pinned.isEmpty() ? SkillRuntime.PINNED : pinned).stream().map(tools::get).filter(Objects::nonNull).filter(fits)
                .forEach(chosen::add);
        tools.values().stream().filter(fits).filter(tool -> !chosen.contains(tool))
                .sorted(Comparator.comparingLong((Tool tool) -> -matches(tool, words))
                        .thenComparing(Tool::name))
                .filter(tool -> matches(tool, words) > 0)
                .limit(SkillRuntime.MAX_OFFERED - chosen.size())
                .forEach(chosen::add);
        return chosen.stream().map(tool -> new SkillRuntime.Offered(SkillRuntime.wireName(tool.name()), tool.name(), tool.description()
                + " Risco: " + tool.baseRisk().wire() + ".", tool.inputSchema())).toList();
    }

    private static long matches(Tool tool, Set<String> words) {
        String haystack = Normalizer.normalize((tool.name() + " " + tool.description())
                .toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return words.stream().filter(haystack::contains).count();
    }
}
