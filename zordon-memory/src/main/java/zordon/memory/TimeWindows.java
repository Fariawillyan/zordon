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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** "hoje", "ontem" e "esta semana" numa consulta viram janela de tempo (SPEC-021 §3). */
public final class TimeWindows {

    public record Window(String label, Instant since, Instant until) {}

    public static Optional<Window> in(String text, ZoneId zone, Instant now) {
        String plain = plain(text);
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (plain.matches(".*\\banteontem\\b.*")) {
            return Optional.of(day("anteontem", today.minusDays(2), zone));
        }
        if (plain.matches(".*\\bontem\\b.*")) {
            return Optional.of(day("ontem", today.minusDays(1), zone));
        }
        if (plain.matches(".*\\bhoje\\b.*")) {
            return Optional.of(day("hoje", today, zone));
        }
        if (plain.matches(".*\\bsemana passada\\b.*")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return Optional.of(new Window("semana passada", monday.atStartOfDay(zone).toInstant(),
                    monday.plusWeeks(1).atStartOfDay(zone).toInstant()));
        }
        if (plain.matches(".*\\b(esta|essa|nesta|nessa) semana\\b.*") || plain.matches("^semana$")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return Optional.of(new Window("esta semana", monday.atStartOfDay(zone).toInstant(),
                    today.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        return Optional.empty();
    }

    private static Window day(String label, LocalDate day, ZoneId zone) {
        return new Window(label, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    static String plain(String text) {
        return Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private TimeWindows() {}
}
