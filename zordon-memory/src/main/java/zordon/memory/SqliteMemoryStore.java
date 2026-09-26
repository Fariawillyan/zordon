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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A conversa e os fatos: o que o Zordon lembra (SPEC-021, ADR-0008).
 *
 * <p>Parte do {@link ZordonDatabase}. Até a SPEC-035 esta classe também era
 * tarefas, automações, achados, eventos de segurança, conhecimento e uso — 1.363
 * linhas e 48 métodos públicos. O banco e o monitor continuam únicos; o que se
 * separou foi a responsabilidade.
 */
public final class SqliteMemoryStore implements MemoryStore {

    static final int CANDIDATES = 30;

    private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);
    private final ZordonDatabase database;
    private final Sql sql;
    private final Clock clock;
    private final Embedder embedder;
    private final MemoryConversation conversation;

    SqliteMemoryStore(ZordonDatabase database) {
        this.database = database;
        this.sql = database.sql();
        this.clock = database.clock();
        this.embedder = database.embedder();
        this.conversation = new MemoryConversation(sql);
    }

    /**
     * Fecha o banco inteiro, e não só a memória.
     *
     * <p>O contrato {@link MemoryStore} é {@code AutoCloseable} desde antes da
     * divisão, quando memória e banco eram a mesma classe. A conexão continua
     * sendo uma só: fechar por aqui é fechar o {@link ZordonDatabase}.
     */
    @Override
    public void close() {
        database.close();
    }

    // conversa ----------------------------------------------------------------

    @Override
    public void session(String sessionId, String title, Instant startedAt) {
        conversation.session(sessionId, title, startedAt);
    }

    @Override
    public void message(String sessionId, String turnId, String role, String content, Instant ts) {
        conversation.message(sessionId, turnId, role, content, ts);
    }

    @Override
    public List<StoredLine> history(String sessionId, int limit) {
        return conversation.history(sessionId, limit);
    }

    @Override
    public List<StoredLine> turn(String turnId) {
        return conversation.turn(turnId);
    }

    // fatos -------------------------------------------------------------------

    @Override
    public Fact remember(NewFact fact) {
        synchronized (sql) {
            String normalized = normalize(fact.content());
            Instant now = clock.instant();
            if (!fact.corrects()) {
                Fact existing = one("SELECT * FROM fact WHERE lower(subject) = lower(?) AND normalized = ? "
                        + "AND superseded_by IS NULL AND (expires_at IS NULL OR expires_at > ?)",
                        fact.subject(), normalized, now.toString());
                if (existing != null) {
                    return existing;
                }
            }
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
            if (embedder != null) {
                embedder.indexed(stored);
            }
            return stored;
        }
    }

    @Override
    public List<MemoryHit> search(RecallQuery query) {
        synchronized (sql) {
            Instant now = clock.instant();
            List<String> params = new ArrayList<>(List.of(now.toString()));
            String filter = MemorySearchPolicy.filter(query, params);
            Map<String, Double> fused = MemorySearchPolicy.fuse(rankings(query, filter, params));
            List<MemoryHit> hits = new ArrayList<>();
            for (Map.Entry<String, Double> entry : fused.entrySet()) {
                Fact fact = one("SELECT * FROM fact WHERE id = ?", entry.getKey());
                if (MemorySearchPolicy.accepted(fact, query, now)) {
                    hits.add(new MemoryHit(fact, entry.getValue() * MemorySearchPolicy.weight(fact, now)));
                }
            }
            MemorySearchPolicy.order(hits);
            return List.copyOf(hits.subList(0, Math.min(query.limit(), hits.size())));
        }
    }

    /** As listas que a fusão combina: texto, janela e vetores. */
    private List<List<String>> rankings(RecallQuery query, String filter, List<String> params) {
        List<List<String>> rankings = new ArrayList<>();
        String match = MemorySearchPolicy.ftsQuery(query.text());
        if (!match.isEmpty()) {
            List<String> lexical = new ArrayList<>();
            List<String> all = new ArrayList<>(List.of(match));
            all.addAll(params);
            sql.ids("SELECT f.id FROM fact_fts JOIN fact f ON f.rowid = fact_fts.rowid WHERE fact_fts MATCH ?"
                    + filter + " ORDER BY bm25(fact_fts) LIMIT " + CANDIDATES, all, lexical);
            rankings.add(lexical);
        }
        boolean windowed = query.since() != null || query.until() != null;
        if (windowed || (match.isEmpty() && !query.kinds().isEmpty())) {
            // Janela ou tipo sem palavra útil: os mais recentes dentro do filtro são uma lista também.
            List<String> recent = new ArrayList<>();
            sql.ids("SELECT f.id FROM fact f WHERE 1 = 1" + filter + " ORDER BY f.observed_at DESC LIMIT "
                    + CANDIDATES, params, recent);
            rankings.add(recent);
        }
        if (embedder != null && !query.text().isBlank()) {
            rankings.add(embedder.nearest(query.text(), CANDIDATES));
        }
        return rankings;
    }

    /** Confiança, recência e acessos (Memória §4). */
    static double weight(Fact fact, Instant now) {
        return MemorySearchPolicy.weight(fact, now);
    }

    /** Palavras úteis viram termos com prefixo, unidos por OR; o resto é ignorado. */
    static String ftsQuery(String text) {
        return MemorySearchPolicy.ftsQuery(text);
    }

    @Override
    public List<Fact> facts(FactKind kind, String subject, int limit) {
        synchronized (sql) {
            StringBuilder sqlText = new StringBuilder(
                    "SELECT * FROM fact WHERE superseded_by IS NULL AND (expires_at IS NULL OR expires_at > ?)");
            List<String> params = new ArrayList<>(List.of(clock.instant().toString()));
            if (kind != null) {
                sqlText.append(" AND kind = ?");
                params.add(kind.name());
            }
            if (subject != null && !subject.isBlank()) {
                sqlText.append(" AND lower(subject) = lower(?)");
                params.add(subject);
            }
            sqlText.append(" ORDER BY observed_at DESC, id LIMIT ").append(Math.max(1, Math.min(limit, 500)));
            return many(sqlText.toString(), params);
        }
    }

    @Override
    public void touched(Iterable<String> factIds) {
        synchronized (sql) {
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
    }

    @Override
    public boolean forget(String factId) {
        synchronized (sql) {
            try (var delete = sql.prepare("DELETE FROM fact WHERE id = ?")) {
                delete.setString(1, factId);
                boolean gone = delete.executeUpdate() > 0;
                if (gone) {
                    log.info("fato {} esquecido a pedido do usuário", factId);
                    if (embedder != null) {
                        embedder.forgotten(factId);
                    }
                }
                return gone;
            } catch (SQLException e) {
                throw Sql.failure("esquecer", e);
            }
        }
    }

    @Override
    public int forgetSubject(String subject) {
        synchronized (sql) {
            List<String> ids = new ArrayList<>();
            sql.ids("SELECT id FROM fact WHERE lower(subject) = lower(?)", List.of(subject), ids);
            int count = 0;
            for (String id : ids) {
                count += forget(id) ? 1 : 0;
            }
            return count;
        }
    }

    // destilação ------------------------------------------------------------

    @Override
    public void enqueueDistill(String turnId, String sessionId, boolean tainted, Instant now) {
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

    @Override
    public List<DistillJob> dueDistill(Instant now, int limit) {
        synchronized (sql) {
            try (var query = sql.prepare(
                    "SELECT turn_id, session_id, tainted, attempts FROM distill_queue WHERE state = 'pending' "
                            + "AND next_at <= ? ORDER BY next_at LIMIT ?")) {
                query.setString(1, now.toString());
                query.setInt(2, limit);
                List<DistillJob> out = new ArrayList<>();
                try (var row = query.executeQuery()) {
                    while (row.next()) {
                        out.add(new DistillJob(row.getString(1), row.getString(2), row.getInt(3) == 1, row.getInt(4)));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw Sql.failure("fila de destilação", e);
            }
        }
    }

    @Override
    public void distilled(String turnId) {
        synchronized (sql) {
            sql.update("UPDATE distill_queue SET state = 'done', attempts = attempts + 1 WHERE turn_id = ?", turnId);
        }
    }

    @Override
    public void distillFailed(String turnId, String error, Instant nextAt, boolean giveUp) {
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

    @Override
    public Map<String, Object> stats() {
        synchronized (sql) {
            return MemoryStats.read(database, sql, embedder);
        }
    }

    // apoio -------------------------------------------------------------------

    static String normalize(String text) {
        return TimeWindows.plain(text).replaceAll("\\s+", " ").replaceAll("[\\s.!;,]+$", "").strip();
    }
    private Fact one(String sqlText, String... params) {
        List<Fact> found = many(sqlText, List.of(params));
        return found.isEmpty() ? null : found.getFirst();
    }

    private List<Fact> many(String sqlText, List<String> params) {
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
