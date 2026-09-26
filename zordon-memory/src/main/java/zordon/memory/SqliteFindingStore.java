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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Os achados da defesa (SPEC-026).
 *
 * <p>Parte do {@link ZordonDatabase}: divisão feita na SPEC-035, quando os sete
 * contratos deixaram de morar numa classe só. O banco e o monitor continuam
 * únicos — o que se separou foi a responsabilidade.
 */
public final class SqliteFindingStore implements FindingStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteFindingStore.class);

    private final Sql sql;
    private final Clock clock;

    SqliteFindingStore(ZordonDatabase database) {
        this.sql = database.sql();
        this.clock = database.clock();
    }

    // achados da defesa (SPEC-026) ------------------------------------------

    @Override
    public void finding(FindingRow finding) {
        synchronized (sql) {
            try (PreparedStatement upsert = sql.prepare(
                    "INSERT INTO finding (id, severity, detector, subject_kind, subject_id, title, rationale, "
                            + "signals_json, count, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(id) DO UPDATE SET severity = excluded.severity, detector = excluded.detector, "
                            + "title = excluded.title, rationale = excluded.rationale, signals_json = excluded.signals_json, "
                            + "count = excluded.count, last_seen = excluded.last_seen")) {
                upsert.setString(1, finding.id());
                upsert.setString(2, finding.severity());
                upsert.setString(3, finding.detector());
                upsert.setString(4, finding.subjectKind());
                upsert.setString(5, finding.subjectId());
                upsert.setString(6, finding.title());
                upsert.setString(7, finding.rationale());
                upsert.setString(8, finding.signalsJson());
                upsert.setInt(9, finding.count());
                upsert.setString(10, finding.firstSeen().toString());
                upsert.setString(11, finding.lastSeen().toString());
                upsert.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("achado", e);
            }
        }
    }

    @Override
    public List<FindingRow> findings(Instant since, List<String> severities, int limit) {
        synchronized (sql) {
            StringBuilder sqlText = new StringBuilder("SELECT id, severity, detector, subject_kind, subject_id, title, "
                    + "rationale, signals_json, count, first_seen, last_seen, acknowledged_at FROM finding WHERE 1 = 1");
            List<String> params = new ArrayList<>();
            if (since != null) {
                sqlText.append(" AND last_seen >= ?");
                params.add(since.toString());
            }
            if (severities != null && !severities.isEmpty()) {
                sqlText.append(" AND severity IN (").append(severities.stream().map(item -> "?")
                        .collect(Collectors.joining(","))).append(')');
                params.addAll(severities);
            }
            sqlText.append(" ORDER BY last_seen DESC LIMIT ").append(Math.max(1, Math.min(limit, 500)));
            try (PreparedStatement query = sql.prepare(sqlText.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    query.setString(i + 1, params.get(i));
                }
                List<FindingRow> out = new ArrayList<>();
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        String acknowledged = row.getString(12);
                        out.add(new FindingRow(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                                row.getString(5), row.getString(6), row.getString(7), row.getString(8), row.getInt(9),
                                Instant.parse(row.getString(10)), Instant.parse(row.getString(11)),
                                acknowledged == null ? null : Instant.parse(acknowledged)));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw Sql.failure("achados", e);
            }
        }
    }

    @Override
    public boolean acknowledgeFinding(String id, Instant at) {
        synchronized (sql) {
            try (PreparedStatement update = sql.prepare(
                    "UPDATE finding SET acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL")) {
                update.setString(1, at.toString());
                update.setString(2, id);
                return update.executeUpdate() > 0;
            } catch (SQLException e) {
                throw Sql.failure("achado confirmado", e);
            }
        }
    }

    @Override
    public Map<String, Object> findingStats() {
        synchronized (sql) {
            Map<String, Object> out = new LinkedHashMap<>();
            String since = clock.instant().minus(Duration.ofHours(24)).toString();
            try (PreparedStatement query = sql.prepare(
                    "SELECT severity, count(*) FROM finding WHERE last_seen >= ? GROUP BY severity ORDER BY severity")) {
                query.setString(1, since);
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        out.put(row.getString(1), row.getLong(2));
                    }
                }
            } catch (SQLException e) {
                throw Sql.failure("achados", e);
            }
            return Map.of("last24h", out);
        }
    }

}
