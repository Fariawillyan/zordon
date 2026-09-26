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
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O estado e o orçamento das automações (SPEC-025).
 *
 * <p>Parte do {@link ZordonDatabase}: divisão feita na SPEC-035, quando os sete
 * contratos deixaram de morar numa classe só. O banco e o monitor continuam
 * únicos — o que se separou foi a responsabilidade.
 */
public final class SqliteAutomationStateStore implements AutomationStateStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteAutomationStateStore.class);

    private final Sql sql;
    private final Clock clock;

    SqliteAutomationStateStore(ZordonDatabase database) {
        this.sql = database.sql();
        this.clock = database.clock();
    }

    // automações (SPEC-025) ---------------------------------------------------

    @Override
    public long automationTokens(java.time.LocalDate day) {
        synchronized (sql) {
            try (PreparedStatement query = sql.prepare("SELECT tokens FROM automation_budget WHERE day = ?")) {
                query.setString(1, day.toString());
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? row.getLong(1) : 0;
                }
            } catch (SQLException e) {
                throw Sql.failure("orçamento das automações", e);
            }
        }
    }

    @Override
    public long reserveAutomationTokens(java.time.LocalDate day, long requested, long ceiling) {
        synchronized (sql) {
            long reserved = Math.max(0, Math.min(requested, ceiling - automationTokens(day)));
            settleAutomationTokens(day, 0, reserved);
            return reserved;
        }
    }

    @Override
    public void settleAutomationTokens(java.time.LocalDate day, long reserved, long used) {
        synchronized (sql) {
            try (PreparedStatement update = sql.prepare("INSERT INTO automation_budget(day, tokens) VALUES (?, ?) "
                    + "ON CONFLICT(day) DO UPDATE SET tokens = MAX(0, automation_budget.tokens + excluded.tokens)")) {
                update.setString(1, day.toString());
                update.setLong(2, Math.max(0, used) - Math.max(0, reserved));
                update.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("orçamento das automações", e);
            }
        }
    }

    @Override
    public State automationState(String id) {
        synchronized (sql) {
            try (PreparedStatement query = sql.prepare(
                    "SELECT last_fired_at, fired, failures, disabled, reason FROM automation_state WHERE id = ?")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) {
                        return State.fresh(id);
                    }
                    String last = row.getString(1);
                    return new State(id, last == null ? null : Instant.parse(last), row.getInt(2), row.getInt(3),
                            row.getInt(4) == 1, row.getString(5));
                }
            } catch (SQLException e) {
                throw Sql.failure("estado da automação", e);
            }
        }
    }

    @Override
    public void automationFired(String id, Instant at) {
        synchronized (sql) {
            upsertAutomation(id);
            try (PreparedStatement update = sql.prepare(
                    "UPDATE automation_state SET last_fired_at = ?, fired = fired + 1, updated_at = ? WHERE id = ?")) {
                update.setString(1, at.toString());
                update.setString(2, clock.instant().toString());
                update.setString(3, id);
                update.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("disparo da automação", e);
            }
        }
    }

    @Override
    public int automationFinished(String id, boolean ok) {
        synchronized (sql) {
            upsertAutomation(id);
            try (PreparedStatement update = sql.prepare(ok
                    ? "UPDATE automation_state SET failures = 0, updated_at = ? WHERE id = ?"
                    : "UPDATE automation_state SET failures = failures + 1, updated_at = ? WHERE id = ?")) {
                update.setString(1, clock.instant().toString());
                update.setString(2, id);
                update.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("desfecho da automação", e);
            }
            return automationState(id).failures();
        }
    }

    @Override
    public void automationDisabled(String id, boolean disabled, String reason) {
        synchronized (sql) {
            upsertAutomation(id);
            try (PreparedStatement update = sql.prepare(
                    "UPDATE automation_state SET disabled = ?, reason = ?, failures = CASE WHEN ? = 0 THEN 0 ELSE failures END, "
                            + "updated_at = ? WHERE id = ?")) {
                update.setInt(1, disabled ? 1 : 0);
                update.setString(2, reason);
                update.setInt(3, disabled ? 1 : 0);
                update.setString(4, clock.instant().toString());
                update.setString(5, id);
                update.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("automação desativada", e);
            }
        }
    }

    private void upsertAutomation(String id) {
        try (PreparedStatement insert = sql.prepare(
                "INSERT OR IGNORE INTO automation_state (id, updated_at) VALUES (?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, clock.instant().toString());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw Sql.failure("estado da automação", e);
        }
    }

}
