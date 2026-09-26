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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private final ZordonDatabase database;
    private final Sql sql;
    private final Clock clock;
    private final Embedder embedder;
    private final MemoryConversation conversation;
    private final DistillQueue distill;
    private final FactTable facts;

    SqliteMemoryStore(ZordonDatabase database) {
        this.database = database;
        this.sql = database.sql();
        this.clock = database.clock();
        this.embedder = database.embedder();
        this.conversation = new MemoryConversation(sql);
        this.distill = new DistillQueue(sql);
        this.facts = new FactTable(sql);
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
                Fact existing = facts.one("SELECT * FROM fact WHERE lower(subject) = lower(?) AND normalized = ? "
                        + "AND superseded_by IS NULL AND (expires_at IS NULL OR expires_at > ?)",
                        fact.subject(), normalized, now.toString());
                if (existing != null) {
                    return existing;
                }
            }
            Fact stored = facts.insert(fact, normalized, now);
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
            fused.forEach((id, score) -> {
                Fact fact = facts.one("SELECT * FROM fact WHERE id = ?", id);
                if (MemorySearchPolicy.accepted(fact, query, now)) {
                    hits.add(new MemoryHit(fact, score * MemorySearchPolicy.weight(fact, now)));
                }
            });
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
            return facts.many(sqlText.toString(), params);
        }
    }

    @Override
    public void touched(Iterable<String> factIds) {
        synchronized (sql) {
            facts.touch(factIds, clock);
        }
    }

    @Override
    public boolean forget(String factId) {
        synchronized (sql) {
            boolean gone = facts.delete(factId);
            if (gone && embedder != null) {
                embedder.forgotten(factId);
            }
            return gone;
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
        distill.enqueue(turnId, sessionId, tainted, now);
    }

    @Override
    public List<DistillJob> dueDistill(Instant now, int limit) {
        return distill.due(now, limit);
    }

    @Override
    public void distilled(String turnId) {
        distill.done(turnId);
    }

    @Override
    public void distillFailed(String turnId, String error, Instant nextAt, boolean giveUp) {
        distill.failed(turnId, error, nextAt, giveUp);
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
}
