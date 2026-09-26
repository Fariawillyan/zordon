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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A tabela de fatos: gravar (substituindo o que foi corrigido), marcar acesso, apagar e ler. */
final class FactTable {

    private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);

    private final Sql sql;

    FactTable(Sql sql) {
        this.sql = sql;
    }

    /** Grava o fato; quando ele corrige, substitui os anteriores do mesmo tipo e assunto. */
    Fact insert(NewFact fact, String normalized, Instant now) {
        String id = MemoryIds.fact(now);
        try {
            sql.connection().setAutoCommit(false);
            try (var insert = sql.prepare(
                    "INSERT INTO fact (id, kind, subject, content, normalized, confidence, observed_at, expires_at, "
                            + "provenance, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    var supersede = sql.prepare(
                            "UPDATE fact SET superseded_by = ? WHERE kind = ? AND lower(subject) = lower(?) "
                                    + "AND superseded_by IS NULL AND id <> ?")) {
                insert.setString(1, id);
                insert.setString(2, fact.kind().name());
                insert.setString(3, fact.subject());
                insert.setString(4, fact.content());
                insert.setString(5, normalized);
                insert.setDouble(6, fact.confidence());
                insert.setString(7, fact.observedAt().toString());
                insert.setString(8, fact.expiresAt() == null ? null : fact.expiresAt().toString());
                insert.setString(9, fact.provenance());
                insert.setString(10, fact.source());
                insert.executeUpdate();
                if (fact.corrects()) {
                    supersede.setString(1, id);
                    supersede.setString(2, fact.kind().name());
                    supersede.setString(3, fact.subject());
                    supersede.setString(4, id);
                    int replaced = supersede.executeUpdate();
                    log.info("fato {} substitui {} fato(s) de {}", id, replaced, fact.kind());
                }
                sql.connection().commit();
            } catch (SQLException e) {
                sql.connection().rollback();
                throw e;
            } finally {
                sql.connection().setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw Sql.failure("fato", e);
        }
        Fact stored = one("SELECT * FROM fact WHERE id = ?", id);
        log.info("fato {} gravado ({}, {})", id, fact.kind(), fact.source());
        return stored;
    }

    void touch(Iterable<String> factIds, Clock clock) {
        try (var update = sql.prepare(
                "UPDATE fact SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ?")) {
            for (String id : factIds) {
                update.setString(1, clock.instant().toString());
                update.setString(2, id);
                update.addBatch();
            }
            update.executeBatch();
        } catch (SQLException e) {
            throw Sql.failure("acesso", e);
        }
    }

    boolean delete(String factId) {
        try (var delete = sql.prepare("DELETE FROM fact WHERE id = ?")) {
            delete.setString(1, factId);
            boolean gone = delete.executeUpdate() > 0;
            if (gone) {
                log.info("fato {} esquecido a pedido do usuário", factId);
            }
            return gone;
        } catch (SQLException e) {
            throw Sql.failure("esquecer", e);
        }
    }

    Fact one(String sqlText, String... params) {
        List<Fact> found = many(sqlText, List.of(params));
        return found.isEmpty() ? null : found.getFirst();
    }

    List<Fact> many(String sqlText, List<String> params) {
        try (var query = sql.prepare(sqlText)) {
            for (int i = 0; i < params.size(); i++) {
                query.setString(i + 1, params.get(i));
            }
            List<Fact> out = new ArrayList<>();
            try (var row = query.executeQuery()) {
                while (row.next()) {
                    String expires = row.getString("expires_at");
                    out.add(new Fact(row.getString("id"), FactKind.valueOf(row.getString("kind")),
                            row.getString("subject"), row.getString("content"), row.getDouble("confidence"),
                            Instant.parse(row.getString("observed_at")), expires == null ? null : Instant.parse(expires),
                            row.getString("provenance"), row.getString("source"), row.getInt("access_count"),
                            row.getString("superseded_by")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw Sql.failure("leitura de fatos", e);
        }
    }
}
