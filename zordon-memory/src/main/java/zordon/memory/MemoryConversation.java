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

/** Persists the session and message part of the memory contract. */
final class MemoryConversation {

    private final Sql sql;

    MemoryConversation(Sql sql) {
        this.sql = sql;
    }

    void session(String sessionId, String title, Instant startedAt) {
        synchronized (sql) {
            try (var insert = sql.prepare(
                    "INSERT INTO session (id, title, started_at, last_active_at) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT(id) DO UPDATE SET title = COALESCE(excluded.title, session.title)")) {
                insert.setString(1, sessionId);
                insert.setString(2, title);
                insert.setString(3, startedAt.toString());
                insert.setString(4, startedAt.toString());
                insert.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("sessão", e);
            }
        }
    }

    void message(String sessionId, String turnId, String role, String content, Instant ts) {
        synchronized (sql) {
            try (var insert = sql.prepare(
                    "INSERT INTO message (session_id, turn_id, role, content, ts) VALUES (?, ?, ?, ?, ?)");
                    var touch = sql.prepare("UPDATE session SET last_active_at = ? WHERE id = ?")) {
                insert.setString(1, sessionId);
                insert.setString(2, turnId);
                insert.setString(3, role);
                insert.setString(4, content);
                insert.setString(5, ts.toString());
                insert.executeUpdate();
                touch.setString(1, ts.toString());
                touch.setString(2, sessionId);
                touch.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("mensagem", e);
            }
        }
    }

    List<StoredLine> history(String sessionId, int limit) {
        synchronized (sql) {
            List<StoredLine> lines = lines("SELECT session_id, turn_id, role, content, ts FROM message WHERE session_id = ? "
                    + "ORDER BY id DESC LIMIT " + Math.max(1, limit), sessionId);
            java.util.Collections.reverse(lines);
            return lines;
        }
    }

    List<StoredLine> turn(String turnId) {
        synchronized (sql) {
            return lines("SELECT session_id, turn_id, role, content, ts FROM message WHERE turn_id = ? ORDER BY id",
                    turnId);
        }
    }

    private List<StoredLine> lines(String sqlText, String param) {
        try (var query = sql.prepare(sqlText)) {
            query.setString(1, param);
            List<StoredLine> out = new ArrayList<>();
            try (var row = query.executeQuery()) {
                while (row.next()) {
                    out.add(new StoredLine(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                            Instant.parse(row.getString(5))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw Sql.failure("histórico", e);
        }
    }
}
