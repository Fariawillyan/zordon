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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.Decision;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.trace.AcceptanceCriteria;

/** A auditoria append-only com cadeia de hash (SPEC-014). */
class AuditLogTest {

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);

    private SqliteAuditLog open() {
        return new SqliteAuditLog(dir.resolve("audit.db"), new Redactor(), clock);
    }

    private static AuditLog.Entry entry(String callId, Map<String, Object> args, Decision decision) {
        return new AuditLog.Entry(callId, "t1", Principal.user(RequestOrigin.UI), "fs.write", args, decision, "policy");
    }

    private static final Decision ALLOW = new Decision.Allow(RiskLevel.YELLOW, "Escrever 1 arquivo", false);

    @AcceptanceCriteria("SPEC-014/CA-1")
    @Test
    void oBancoRecusaAlterarEApagarEAdulteracaoPorForaApareceNaVerificacao() throws Exception {
        try (SqliteAuditLog log = open()) {
            for (int i = 1; i <= 5; i++) {
                log.begin(entry("c" + i, Map.of("path", "/home/u/dev/a" + i), ALLOW));
            }
            assertThat(log.verify(1000)).isEqualTo(new AuditLog.Verification(true, 5, -1));
        }
        String url = "jdbc:sqlite:" + dir.resolve("audit.db");
        try (var db = DriverManager.getConnection(url); Statement s = db.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE audit SET tool = 'x' WHERE id = 3"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM audit WHERE id = 3"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            // Quem tem o arquivo pode tirar o trigger; a cadeia denuncia.
            s.execute("DROP TRIGGER audit_no_update");
            s.executeUpdate("UPDATE audit SET args_json = '{\"path\":\"/outro\"}' WHERE id = 3");
        }
        try (SqliteAuditLog log = open()) {
            AuditLog.Verification verification = log.verify(1000);
            assertThat(verification.ok()).isFalse();
            assertThat(verification.firstBroken()).isEqualTo(3);
            assertThat(verification.checked()).isEqualTo(2);
        }
    }

    @AcceptanceCriteria("SPEC-014/CA-2")
    @Test
    void aIntencaoVemAntesDoDesfechoInclusiveQuandoNegada() throws Exception {
        try (SqliteAuditLog log = open()) {
            log.begin(entry("ok-1", Map.of("path", "/home/u/dev/a"), ALLOW));
            log.complete("ok-1", new AuditLog.Completion(AuditLog.Status.OK, Duration.ofMillis(12),
                    "1 arquivo escrito", null));
            log.begin(entry("deny-1", Map.of("path", "C:/Windows/x"),
                    new Decision.Deny(RiskLevel.RED, "caminho proibido pela política")));
            log.complete("deny-1", new AuditLog.Completion(AuditLog.Status.CANCELLED, Duration.ZERO, null, "negado"));
            assertThat(log.verify(10).ok()).isTrue();
            assertThat(log.entries()).isEqualTo(4);
        }
        List<String> rows = new ArrayList<>();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("audit.db"));
                Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT call_id, status, decision, tool FROM audit ORDER BY id")) {
            while (r.next()) {
                rows.add(r.getString(1) + " " + r.getString(2) + " " + r.getString(3) + " " + r.getString(4));
            }
        }
        assertThat(rows).containsExactly("ok-1 started allow fs.write", "ok-1 ok allow fs.write",
                "deny-1 started deny fs.write", "deny-1 cancelled deny fs.write");
        try (SqliteAuditLog log = open()) {
            assertThatThrownBy(() -> log.complete("nunca-comecou",
                    new AuditLog.Completion(AuditLog.Status.OK, Duration.ZERO, null, null)))
                    .hasMessageContaining("sem intenção registrada");
        }
    }

    @AcceptanceCriteria("SPEC-014/CA-3")
    @Test
    void segredoNosArgumentosSoFicaMascarado() throws Exception {
        String key = "sk-proj-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6" + "92F";
        try (SqliteAuditLog log = open()) {
            log.begin(entry("s1", Map.of("header", "Authorization: Bearer " + key, "api_key", key), ALLOW));
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("audit.db"));
                Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT args_json FROM audit")) {
            r.next();
            String stored = r.getString(1);
            assertThat(stored).doesNotContain(key).contains("sk-proj-").contains("92F").contains("****");
        }
    }

    @Test
    void reabrirContinuaACadeia() {
        try (SqliteAuditLog log = open()) {
            log.begin(entry("a", Map.of(), ALLOW));
        }
        try (SqliteAuditLog log = open()) {
            log.begin(entry("b", Map.of(), ALLOW));
            assertThat(log.verify(1000)).isEqualTo(new AuditLog.Verification(true, 2, -1));
        }
    }
}
