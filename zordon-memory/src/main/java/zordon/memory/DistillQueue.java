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

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A fila de destilação: turnos à espera de virar fatos, com tentativas e prazo da próxima. */
final class DistillQueue {

    private final Sql sql;

    DistillQueue(Sql sql) {
        this.sql = sql;
    }

    void enqueue(String turnId, String sessionId, boolean tainted, Instant now) {
        synchronized (sql) {
            try (var insert = sql.prepare(
                    "INSERT OR IGNORE INTO distill_queue (turn_id, session_id, tainted, created_at, next_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, turnId);
                insert.setString(2, sessionId);
                insert.setInt(3, tainted ? 1 : 0);
                insert.setString(4, now.toString());
                insert.setString(5, now.toString());
                insert.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("fila de destilação", e);
            }
        }
    }

    List<MemoryStore.DistillJob> due(Instant now, int limit) {
        synchronized (sql) {
            try (var query = sql.prepare(
                    "SELECT turn_id, session_id, tainted, attempts FROM distill_queue WHERE state = 'pending' "
                            + "AND next_at <= ? ORDER BY next_at LIMIT ?")) {
                query.setString(1, now.toString());
                query.setInt(2, limit);
                List<MemoryStore.DistillJob> out = new ArrayList<>();
                try (var row = query.executeQuery()) {
                    while (row.next()) {
                        out.add(new MemoryStore.DistillJob(row.getString(1), row.getString(2), row.getInt(3) == 1, row.getInt(4)));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw Sql.failure("fila de destilação", e);
            }
        }
    }

    void done(String turnId) {
        synchronized (sql) {
            sql.update("UPDATE distill_queue SET state = 'done', attempts = attempts + 1 WHERE turn_id = ?", turnId);
        }
    }

    void failed(String turnId, String error, Instant nextAt, boolean giveUp) {
        synchronized (sql) {
            try (var update = sql.prepare(
                    "UPDATE distill_queue SET attempts = attempts + 1, last_error = ?, next_at = ?, state = ? "
                            + "WHERE turn_id = ?")) {
                update.setString(1, error == null ? null : error.substring(0, Math.min(error.length(), 300)));
                update.setString(2, nextAt.toString());
                update.setString(3, giveUp ? "failed" : "pending");
                update.setString(4, turnId);
                update.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("fila de destilação", e);
            }
        }
    }
}
