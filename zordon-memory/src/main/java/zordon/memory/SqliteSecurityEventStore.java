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
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Os eventos de segurança, append-only e em cadeia de hash (SPEC-027).
 *
 * <p>Parte do {@link ZordonDatabase}: divisão feita na SPEC-035, quando os sete
 * contratos deixaram de morar numa classe só. O banco e o monitor continuam
 * únicos — o que se separou foi a responsabilidade.
 */
public final class SqliteSecurityEventStore implements SecurityEventStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteSecurityEventStore.class);

    private final Sql sql;
    private final Clock clock;

    SqliteSecurityEventStore(ZordonDatabase database) {
        this.sql = database.sql();
        this.clock = database.clock();
    }

    // eventos de segurança (SPEC-027) ---------------------------------------

    @Override
    public String securityEvent(SecurityEventRow event) {
        synchronized (sql) {
            String previous = lastSecurityHash();
            String payload = String.join("\u001f", event.eventId(), event.ts().toString(), event.severity(),
                    event.detector(), event.subject(), String.valueOf(event.findingId()), event.proposed(),
                    event.executed(), event.outcome(), event.authorization(), String.valueOf(event.rollbackAvailable()),
                    String.valueOf(event.userMessageId()), previous);
            String hash = sha256(payload);
            try (PreparedStatement insert = sql.prepare(
                    "INSERT INTO security_event (event_id, ts, severity, detector, subject, finding_id, proposed, "
                            + "executed, outcome, authorization_kind, rollback_available, user_message_id, prev_hash, hash) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, event.eventId());
                insert.setString(2, event.ts().toString());
                insert.setString(3, event.severity());
                insert.setString(4, event.detector());
                insert.setString(5, event.subject());
                insert.setString(6, event.findingId());
                insert.setString(7, event.proposed());
                insert.setString(8, event.executed());
                insert.setString(9, event.outcome());
                insert.setString(10, event.authorization());
                insert.setInt(11, event.rollbackAvailable() ? 1 : 0);
                insert.setString(12, event.userMessageId());
                insert.setString(13, previous);
                insert.setString(14, hash);
                insert.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("evento de segurança", e);
            }
            return hash;
        }
    }

    @Override
    public List<Map<String, Object>> securityEvents(int limit) {
        synchronized (sql) {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement query = sql.prepare(
                    "SELECT event_id, ts, severity, detector, subject, finding_id, proposed, executed, outcome, "
                            + "authorization_kind, rollback_available, user_message_id FROM security_event "
                            + "ORDER BY id DESC LIMIT ?")) {
                query.setInt(1, Math.max(1, Math.min(limit, 500)));
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("eventId", row.getString(1));
                        event.put("ts", row.getString(2));
                        event.put("severity", row.getString(3));
                        event.put("detector", row.getString(4));
                        event.put("subject", row.getString(5));
                        event.put("findingId", row.getString(6));
                        event.put("proposed", row.getString(7));
                        event.put("executed", row.getString(8));
                        event.put("outcome", row.getString(9));
                        event.put("authorization", row.getString(10));
                        event.put("rollbackAvailable", row.getInt(11) == 1);
                        event.put("userMessageId", row.getString(12));
                        out.add(event);
                    }
                }
            } catch (SQLException e) {
                throw Sql.failure("eventos de segurança", e);
            }
            return out;
        }
    }

    @Override
    public boolean securityChainOk() {
        synchronized (sql) {
            try (Statement statement = sql.statement();
                    ResultSet row = statement.executeQuery(
                            "SELECT event_id, ts, severity, detector, subject, finding_id, proposed, executed, outcome, "
                                    + "authorization_kind, rollback_available, user_message_id, prev_hash, hash "
                                    + "FROM security_event ORDER BY id")) {
                String previous = "";
                while (row.next()) {
                    String payload = String.join("\u001f", row.getString(1), row.getString(2), row.getString(3),
                            row.getString(4), row.getString(5), String.valueOf(row.getString(6)), row.getString(7),
                            row.getString(8), row.getString(9), row.getString(10),
                            String.valueOf(row.getInt(11) == 1), String.valueOf(row.getString(12)), previous);
                    if (!previous.equals(row.getString(13)) || !sha256(payload).equals(row.getString(14))) {
                        return false;
                    }
                    previous = row.getString(14);
                }
                return true;
            } catch (SQLException e) {
                throw Sql.failure("cadeia de segurança", e);
            }
        }
    }

    private String lastSecurityHash() {
        try (Statement statement = sql.statement();
                ResultSet row = statement.executeQuery("SELECT hash FROM security_event ORDER BY id DESC LIMIT 1")) {
            return row.next() ? row.getString(1) : "";
        } catch (SQLException e) {
            throw Sql.failure("cadeia de segurança", e);
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
