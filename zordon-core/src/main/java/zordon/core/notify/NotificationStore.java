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
package zordon.core.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** SQLite persistence for pending notifications. */
final class NotificationStore implements AutoCloseable {

    private final Connection db;
    private final ObjectMapper json;

    NotificationStore(Connection db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    void save(ZordonMessage message, Instant expires, boolean grouped) {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO notification (id, ts, severity, body, expires_at) VALUES (?, ?, ?, ?, ?)"
                        + (grouped ? " ON CONFLICT(id) DO UPDATE SET ts = excluded.ts, severity = excluded.severity, "
                                + "body = excluded.body, expires_at = excluded.expires_at, acknowledged_at = NULL" : ""))) {
            insert.setString(1, message.id());
            insert.setString(2, message.ts().toString());
            insert.setString(3, message.severity().wire());
            insert.setString(4, json.writeValueAsString(message.payload()));
            insert.setString(5, expires == null ? null : expires.toString());
            insert.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("notificação não gravada: " + e.getMessage(), e);
        }
    }

    boolean acknowledge(String id, Instant now) {
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE notification SET acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL")) {
            update.setString(1, now.toString());
            update.setString(2, id);
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("confirmação não gravada: " + e.getMessage(), e);
        }
    }

    List<Map<String, Object>> pending(Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement("SELECT body FROM notification WHERE acknowledged_at IS NULL "
                + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY ts, id")) {
            query.setString(1, now.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) out.add(json.readValue(rows.getString(1), new TypeReference<Map<String, Object>>() { }));
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("fila de notificações: " + e.getMessage(), e);
        }
        return out;
    }

    @Override public void close() {
        try { db.close(); }
        catch (SQLException e) { throw new IllegalStateException("fechando a fila de notificações: " + e.getMessage(), e); }
    }
}
