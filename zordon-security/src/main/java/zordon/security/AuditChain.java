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
package zordon.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Confere as últimas linhas da auditoria: cada hash por dentro e o elo com a anterior. */
final class AuditChain {

    /** Uma linha como está no arquivo: o conteúdo e os dois hashes gravados. */
    private record Stored(long id, AuditRow row, String prev, String hash) {}

    private AuditChain() {}

    static AuditLog.Verification verify(Connection db, int lastN) {
        List<Stored> rows = new ArrayList<>();
        try (PreparedStatement q = db.prepareStatement(
                "SELECT id, " + AuditRow.COLUMNS + ", prev_hash, hash FROM audit ORDER BY id DESC LIMIT ?")) {
            q.setInt(1, lastN);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    rows.addFirst(read(r));
                }
            }
        } catch (SQLException e) {
            return new AuditLog.Verification(false, 0, -1);
        }
        int checked = 0;
        for (int i = 0; i < rows.size(); i++) {
            Stored stored = rows.get(i);
            // A primeira linha da janela só se confere por dentro; as seguintes, também pelo elo.
            boolean linked = i == 0
                    ? stored.id() > 1 || AuditRow.GENESIS.equals(stored.prev())
                    : stored.prev().equals(rows.get(i - 1).hash());
            if (!linked || !stored.hash().equals(stored.row().hash(stored.prev()))) {
                return new AuditLog.Verification(false, checked, stored.id());
            }
            checked++;
        }
        return new AuditLog.Verification(true, checked, -1);
    }

    private static Stored read(ResultSet r) throws SQLException {
        long duration = r.getLong(13);
        Long durationMs = r.wasNull() ? null : duration;
        AuditRow row = new AuditRow(r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12),
                durationMs, r.getString(14), r.getString(15));
        return new Stored(r.getLong(1), row, r.getString(16), r.getString(17));
    }
}
