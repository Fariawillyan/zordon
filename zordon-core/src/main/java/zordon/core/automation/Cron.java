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

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/**
 * Cron de 5 campos (minuto, hora, dia, mês, dia da semana), com {@code *}, listas,
 * faixas e passos. Dia e dia da semana restritos ao mesmo tempo casam com um OU com o
 * outro, como no cron tradicional.
 */
public final class Cron {

    /**
     * O casamento de dia: dia-do-mês e dia-da-semana andam juntos porque o cron
     * os combina — com os dois preenchidos, qualquer um que bata serve.
     */
    private record Days(BitSet ofMonth, BitSet ofWeek, boolean anyDay, boolean anyWeekday) {}

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet months;
    private final Days days;

    private Cron(BitSet minutes, BitSet hours, BitSet months, Days days) {
        this.minutes = minutes;
        this.hours = hours;
        this.months = months;
        this.days = days;
    }

    public static Cron parse(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("cron ausente");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("cron precisa de 5 campos (min hora dia mês dia-da-semana): " + expression);
        }
        BitSet weekdays = field(fields[4], 0, 7, "dia da semana");
        if (weekdays.get(7)) {
            weekdays.set(0);   // 7 também é domingo
        }
        return new Cron(field(fields[0], 0, 59, "minuto"), field(fields[1], 0, 23, "hora"),
                field(fields[3], 1, 12, "mês"),
                new Days(field(fields[2], 1, 31, "dia"), weekdays, fields[2].equals("*"), fields[4].equals("*")));
    }

    private static BitSet field(String text, int min, int max, String name) {
        BitSet out = new BitSet(max + 1);
        for (String part : text.split(",")) {
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = number(part.substring(slash + 1), 1, max, name);
                range = part.substring(0, slash);
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                from = number(range.substring(0, range.indexOf('-')), min, max, name);
                to = number(range.substring(range.indexOf('-') + 1), min, max, name);
                if (from > to) {
                    throw new IllegalArgumentException(name + ": faixa invertida " + range);
                }
            } else {
                from = number(range, min, max, name);
                to = slash >= 0 ? max : from;
            }
            for (int value = from; value <= to; value += step) {
                out.set(value);
            }
        }
        return out;
    }

    private static int number(String text, int min, int max, String name) {
        try {
            int value = Integer.parseInt(text);
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " fora de " + min + "–" + max + ": " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " inválido: " + text);
        }
    }

    /** O primeiro horário depois de {@code after}, no fuso dele. Até dois anos à frente. */
    public ZonedDateTime next(ZonedDateTime after) {
        ZonedDateTime candidate = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime limit = after.plusYears(2);
        while (candidate.isBefore(limit)) {
            if (!months.get(candidate.getMonthValue())) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }
            if (!dayMatches(candidate)) {
                candidate = candidate.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.plusHours(1).withMinute(0);
                continue;
            }
            if (!minutes.get(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException("cron sem próximo horário em dois anos");
    }

    private boolean dayMatches(ZonedDateTime at) {
        boolean day = days.ofMonth().get(at.getDayOfMonth());
        boolean weekday = days.ofWeek().get(at.getDayOfWeek().getValue() % 7);
        if (days.anyDay() && days.anyWeekday()) {
            return true;
        }
        if (days.anyDay()) {
            return weekday;
        }
        if (days.anyWeekday()) {
            return day;
        }
        return day || weekday;
    }
}
