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
package zordon.core.memory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import zordon.memory.Fact;
import zordon.memory.FactKind;
import zordon.memory.NewFact;

/** Regras puras de classificação e apresentação da memória. */
final class MemoryText {

    static String line(Fact fact, ZoneId zone) {
        return "- " + LocalDate.ofInstant(fact.observedAt(), zone) + " · " + label(fact.kind()) + " · "
                + fact.subject() + ": " + fact.content();
    }

    private static String label(FactKind kind) {
        return switch (kind) {
            case PREFERENCE -> "preferência";
            case PROJECT -> "projeto";
            case ENTITY -> "fato";
            case EVENT -> "evento";
            case PROCEDURE -> "procedimento";
        };
    }

    static FactKind kindOf(String content) {
        String plain = java.text.Normalizer.normalize(content.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (plain.matches(".*\\b(prefiro|prefere|gosto|gosta|nao gosto|odeio|detesto|quero sempre|sempre quero)\\b.*")) {
            return FactKind.PREFERENCE;
        }
        if (plain.matches(".*\\bpara .+,? (rode|roda|use|usa|execute|faca|digite)\\b.*")) {
            return FactKind.PROCEDURE;
        }
        if (plain.matches(".*\\bprojeto\\b.*")) {
            return FactKind.PROJECT;
        }
        return FactKind.ENTITY;
    }

    static String subjectOf(FactKind kind, String content) {
        var project = java.util.regex.Pattern.compile("(?i)\\bprojeto\\s+([\\p{L}\\p{N}_.-]+)")
                .matcher(content);
        if (kind == FactKind.PROJECT && project.find()) {
            return "projeto " + project.group(1);
        }
        List<String> words = java.util.Arrays.stream(content.split("[^\\p{L}\\p{N}_.-]+"))
                .filter(word -> word.length() > 2)
                .filter(word -> !Set.of("que", "para", "com", "sem", "uma", "meu", "minha", "eu", "prefiro", "gosto",
                        "sempre", "nunca", "não", "nao").contains(word.toLowerCase(java.util.Locale.ROOT)))
                .limit(4).toList();
        String subject = words.isEmpty() ? content : String.join(" ", words);
        return subject.length() > NewFact.MAX_SUBJECT ? subject.substring(0, NewFact.MAX_SUBJECT) : subject;
    }

    private MemoryText() {}
}
