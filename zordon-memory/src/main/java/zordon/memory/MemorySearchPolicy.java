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
package zordon.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Regras puras da recuperação de memória, sem acesso ao banco. */
final class MemorySearchPolicy {

    private static final int RRF_K = 60;
    private static final Set<String> STOPWORDS = Set.of("a", "o", "as", "os", "um", "uma", "de", "da", "do", "das",
            "dos", "e", "em", "no", "na", "nos", "nas", "que", "se", "para", "pra", "por", "com", "sem", "eu", "voce",
            "me", "meu", "minha", "isso", "isto", "esse", "essa", "este", "esta", "qual", "quais", "como", "onde",
            "quando", "foi", "era", "ser", "ter", "tem", "ao", "aos", "mais", "menos", "muito", "zordon", "hoje",
            "ontem", "anteontem", "semana", "passada", "sobre", "lembra", "lembre", "sabe");

    private MemorySearchPolicy() {}

    static String filter(RecallQuery query, List<String> params) {
        StringBuilder filter = new StringBuilder(
                " AND f.superseded_by IS NULL AND (f.expires_at IS NULL OR f.expires_at > ?)");
        if (!query.kinds().isEmpty()) {
            filter.append(" AND f.kind IN (").append(query.kinds().stream().map(kind -> "?")
                    .collect(Collectors.joining(","))).append(')');
            query.kinds().stream().map(Enum::name).sorted().forEach(params::add);
        }
        if (query.since() != null) {
            filter.append(" AND f.observed_at >= ?");
            params.add(query.since().toString());
        }
        if (query.until() != null) {
            filter.append(" AND f.observed_at < ?");
            params.add(query.until().toString());
        }
        return filter.toString();
    }

    static Map<String, Double> fuse(List<List<String>> rankings) {
        Map<String, Double> fused = new HashMap<>();
        for (List<String> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                fused.merge(ranking.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
        return fused;
    }

    static boolean accepted(Fact fact, RecallQuery query, Instant now) {
        return fact != null && fact.active(now)
                && (query.kinds().isEmpty() || query.kinds().contains(fact.kind()))
                && (query.since() == null || !fact.observedAt().isBefore(query.since()))
                && (query.until() == null || fact.observedAt().isBefore(query.until()));
    }

    static double weight(Fact fact, Instant now) {
        double confidence = 0.5 + 0.5 * fact.confidence();
        Duration halfLife = fact.kind().halfLife();
        double recency = 1.0;
        if (halfLife != null) {
            double age = Math.max(0, Duration.between(fact.observedAt(), now).toSeconds());
            recency = Math.pow(0.5, age / halfLife.toSeconds());
        }
        return confidence * recency * (1 + 0.1 * Math.log(1 + fact.accessCount()));
    }

    static String ftsQuery(String text) {
        List<String> terms = new ArrayList<>();
        for (String word : TimeWindows.plain(text).split("[^a-z0-9]+")) {
            if (word.length() < 2 || STOPWORDS.contains(word)) {
                continue;
            }
            String term = word.length() >= 6 ? word.substring(0, Math.max(5, word.length() - 4)) : word;
            String quoted = "\"" + term + "\"" + (word.length() >= 4 ? "*" : "");
            if (!terms.contains(quoted)) {
                terms.add(quoted);
            }
        }
        return String.join(" OR ", terms);
    }

    static void order(List<MemoryHit> hits) {
        hits.sort(Comparator.comparingDouble(MemoryHit::score).reversed().thenComparing(hit -> hit.fact().id()));
    }
}
