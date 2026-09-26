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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O consumo de tokens por dia, ator e modelo (SPEC-029).
 *
 * <p>Parte do {@link ZordonDatabase}: divisão feita na SPEC-035, quando os sete
 * contratos deixaram de morar numa classe só. O banco e o monitor continuam
 * únicos — o que se separou foi a responsabilidade.
 */
public final class SqliteUsageStore implements UsageStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteUsageStore.class);

    private final Sql sql;
    private final Clock clock;

    SqliteUsageStore(ZordonDatabase database) {
        this.sql = database.sql();
        this.clock = database.clock();
    }

    // uso de tokens (SPEC-029) -----------------------------------------------

    @Override
    public void recordUsage(java.time.LocalDate day, String provider, String model, String actor,
            long input, long output) {
        synchronized (sql) {
            try (PreparedStatement upsert = sql.prepare(
                    "INSERT INTO usage_daily (day, provider, model, actor, input_tokens, output_tokens, calls) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 1) ON CONFLICT(day, provider, model, actor) DO UPDATE SET "
                            + "input_tokens = input_tokens + excluded.input_tokens, "
                            + "output_tokens = output_tokens + excluded.output_tokens, calls = calls + 1")) {
                upsert.setString(1, day.toString());
                upsert.setString(2, provider == null ? "?" : provider);
                upsert.setString(3, model == null ? "?" : model);
                upsert.setString(4, actor == null ? "?" : actor);
                upsert.setLong(5, Math.max(0, input));
                upsert.setLong(6, Math.max(0, output));
                upsert.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("uso", e);
            }
        }
    }

    @Override
    public Map<String, Object> usageSummary(int days) {
        synchronized (sql) {
            String since = clock.instant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    .minusDays(Math.max(0, days - 1)).toString();
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> byActor = new LinkedHashMap<>();
            Map<String, Object> byDay = new LinkedHashMap<>();
            long input = 0;
            long output = 0;
            long calls = 0;
            try (PreparedStatement query = sql.prepare(
                    "SELECT day, actor, sum(input_tokens), sum(output_tokens), sum(calls) FROM usage_daily "
                            + "WHERE day >= ? GROUP BY day, actor ORDER BY day DESC, actor")) {
                query.setString(1, since);
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        long in = row.getLong(3);
                        long ou = row.getLong(4);
                        input += in;
                        output += ou;
                        calls += row.getLong(5);
                        byActor.merge(row.getString(2), in + ou, (a, b) -> ((Number) a).longValue()
                                + ((Number) b).longValue());
                        byDay.merge(row.getString(1), in + ou, (a, b) -> ((Number) a).longValue()
                                + ((Number) b).longValue());
                    }
                }
            } catch (SQLException e) {
                throw Sql.failure("uso", e);
            }
            out.put("since", since);
            out.put("inputTokens", input);
            out.put("outputTokens", output);
            out.put("calls", calls);
            out.put("byActor", byActor);
            out.put("byDay", byDay);
            return out;
        }
    }

}
