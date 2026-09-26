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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A tabela da auditoria em SQLite. Os triggers recusam {@code UPDATE} e
 * {@code DELETE}; a cadeia de hash torna visível quem contornar os triggers
 * mexendo no arquivo. Quem chama serializa o acesso.
 */
final class AuditStore {

    private final Connection db;
    private String lastHash;

    private AuditStore(Connection db) throws SQLException {
        this.db = db;
        this.lastHash = queryLastHash();
    }

    static AuditStore open(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS audit (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          ts TEXT NOT NULL, call_id TEXT NOT NULL, turn_id TEXT,
                          actor TEXT NOT NULL, origin TEXT NOT NULL, tool TEXT NOT NULL,
                          args_json TEXT NOT NULL, risk TEXT NOT NULL, decision TEXT NOT NULL,
                          decided_by TEXT NOT NULL, status TEXT NOT NULL, duration_ms INTEGER,
                          result_summary TEXT, error TEXT,
                          prev_hash TEXT NOT NULL, hash TEXT NOT NULL)""");
                s.execute("CREATE INDEX IF NOT EXISTS audit_call ON audit(call_id)");
                s.execute("""
                        CREATE TRIGGER IF NOT EXISTS audit_no_update BEFORE UPDATE ON audit
                        BEGIN SELECT RAISE(ABORT, 'audit is append-only'); END""");
                s.execute("""
                        CREATE TRIGGER IF NOT EXISTS audit_no_delete BEFORE DELETE ON audit
                        BEGIN SELECT RAISE(ABORT, 'audit is append-only'); END""");
            }
            return new AuditStore(db);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("auditoria indisponível em " + file + ": " + e.getMessage(), e);
        }
    }

    /** Acrescenta a linha encadeada na anterior. @return o id da linha */
    long append(AuditRow row) {
        String hash = row.hash(lastHash);
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO audit (" + AuditRow.COLUMNS
                + ", prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            Object[] values = row.values();
            for (int i = 0; i < values.length; i++) {
                insert.setObject(i + 1, values[i]);
            }
            insert.setString(values.length + 1, lastHash);
            insert.setString(values.length + 2, hash);
            insert.executeUpdate();
            lastHash = hash;
            try (ResultSet keys = insert.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    /** A intenção ainda aberta de uma chamada. */
    AuditRow started(String callId) {
        try (PreparedStatement q = db.prepareStatement(
                "SELECT turn_id, actor, origin, tool, args_json, risk, decision, decided_by FROM audit "
                        + "WHERE call_id = ? AND status = 'started' ORDER BY id LIMIT 1")) {
            q.setString(1, callId);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalStateException("desfecho sem intenção registrada: " + callId);
                }
                return new AuditRow(null, callId, r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8), null, null, null, null);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    AuditLog.Verification verify(int lastN) {
        return AuditChain.verify(db, lastN);
    }

    long entries() {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM audit")) {
            return r.next() ? r.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    void close() {
        try {
            db.close();
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    private String queryLastHash() throws SQLException {
        try (Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT hash FROM audit ORDER BY id DESC LIMIT 1")) {
            return r.next() ? r.getString(1) : AuditRow.GENESIS;
        }
    }
}
