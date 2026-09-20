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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import zordon.api.trace.Spec;

/**
 * A auditoria em SQLite (SPEC-014). Os triggers recusam {@code UPDATE} e
 * {@code DELETE}; a cadeia de hash torna visível quem contornar os triggers
 * mexendo no arquivo.
 *
 * <p>{@code hash = sha256(prev_hash, separador, campos canônicos)}. A primeira
 * linha encadeia num {@code prev_hash} de 64 zeros.
 */
@Spec("SPEC-014")
public final class SqliteAuditLog implements AuditLog {

    static final String GENESIS = "0".repeat(64);
    /** Separa campos no texto canônico: não aparece em JSON nem em texto digitado. */
    private static final String FIELD = String.valueOf((char) 31);
    private static final String FIELDS =
            "ts, call_id, turn_id, actor, origin, tool, args_json, risk, decision, decided_by, status, "
                    + "duration_ms, result_summary, error";
    private static final ObjectMapper json = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Connection db;
    private final Redactor redactor;
    private final Clock clock;
    private String lastHash;

    public SqliteAuditLog(Path file, Redactor redactor, Clock clock) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
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
            lastHash = queryLastHash();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("auditoria indisponível em " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized long begin(Entry entry) {
        String args = toJson(redactor.redact(entry.args()));
        return insert(new Row(clock.instant().toString(), entry.callId(), entry.turnId(), entry.principal().actor(),
                entry.principal().origin().wire(), entry.tool(), args, entry.decision().risk().wire(),
                entry.decision().wire(), entry.decidedBy(), Status.STARTED.wire(), null,
                redactor.redact(entry.decision().reason()), null));
    }

    @Override
    public synchronized void complete(String callId, Status status, Duration took, String summary, String error) {
        try (PreparedStatement q = db.prepareStatement(
                "SELECT turn_id, actor, origin, tool, args_json, risk, decision, decided_by FROM audit "
                        + "WHERE call_id = ? AND status = 'started' ORDER BY id LIMIT 1")) {
            q.setString(1, callId);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalStateException("desfecho sem intenção registrada: " + callId);
                }
                insert(new Row(clock.instant().toString(), callId, r.getString(1), r.getString(2), r.getString(3),
                        r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), status.wire(),
                        took == null ? null : took.toMillis(), redactor.redact(summary), redactor.redact(error)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Verification verify(int lastN) {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement q = db.prepareStatement(
                "SELECT id, " + FIELDS + ", prev_hash, hash FROM audit ORDER BY id DESC LIMIT ?")) {
            q.setInt(1, lastN);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Object[] row = new Object[17];
                    for (int i = 0; i < row.length; i++) {
                        row[i] = r.getObject(i + 1);
                    }
                    rows.addFirst(row);
                }
            }
        } catch (SQLException e) {
            return new Verification(false, 0, -1);
        }
        int checked = 0;
        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            long id = ((Number) row[0]).longValue();
            Row fields = new Row((String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
                    (String) row[6], (String) row[7], (String) row[8], (String) row[9], (String) row[10],
                    (String) row[11], row[12] == null ? null : ((Number) row[12]).longValue(), (String) row[13],
                    (String) row[14]);
            String prev = (String) row[15];
            String hash = (String) row[16];
            // A primeira linha da janela só se confere por dentro; as seguintes, também pelo elo.
            boolean linked = i == 0 ? id > 1 || GENESIS.equals(prev) : prev.equals(rows.get(i - 1)[16]);
            if (!linked || !hash.equals(hash(prev, fields))) {
                return new Verification(false, checked, id);
            }
            checked++;
        }
        return new Verification(true, checked, -1);
    }

    @Override
    public synchronized long entries() {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM audit")) {
            return r.next() ? r.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    private record Row(String ts, String callId, String turnId, String actor, String origin, String tool,
            String args, String risk, String decision, String decidedBy, String status, Long durationMs,
            String summary, String error) {

        String canonical() {
            List<String> parts = new ArrayList<>();
            for (Object value : new Object[] {ts, callId, turnId, actor, origin, tool, args, risk, decision, decidedBy,
                    status, durationMs, summary, error}) {
                parts.add(value == null ? "" : value.toString());
            }
            return String.join(FIELD, parts);
        }
    }

    private long insert(Row row) {
        String hash = hash(lastHash, row);
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO audit (" + FIELDS
                + ", prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            Object[] values = {row.ts(), row.callId(), row.turnId(), row.actor(), row.origin(), row.tool(), row.args(),
                    row.risk(), row.decision(), row.decidedBy(), row.status(), row.durationMs(), row.summary(),
                    row.error(), lastHash, hash};
            for (int i = 0; i < values.length; i++) {
                insert.setObject(i + 1, values[i]);
            }
            insert.executeUpdate();
            lastHash = hash;
            try (ResultSet keys = insert.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria: " + e.getMessage(), e);
        }
    }

    private String queryLastHash() throws SQLException {
        try (Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT hash FROM audit ORDER BY id DESC LIMIT 1")) {
            return r.next() ? r.getString(1) : GENESIS;
        }
    }

    private static String hash(String prev, Row row) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prev.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 30);
            sha.update(row.canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
