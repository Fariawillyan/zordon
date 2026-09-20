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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A memória num SQLite só, {@code ~/.zordon/zordon.db} (SPEC-021, ADR-0008).
 * Uma conexão, métodos sincronizados: é um assistente pessoal, não um servidor.
 */
public final class SqliteMemoryStore implements MemoryStore, TaskStore, AutomationStateStore, FindingStore, SecurityEventStore, KnowledgeStore, UsageStore {

    /** As migrações, em ordem. Só para frente (Memória §7). */
    static final List<String> MIGRATIONS = List.of("V001__memoria.sql", "V002__tarefas.sql", "V003__automacoes.sql",
            "V004__orcamento_automacoes.sql", "V005__achados.sql",
            "V006__eventos_de_seguranca.sql", "V007__conhecimento.sql", "V008__uso.sql");
    static final int RRF_K = 60;
    static final int CANDIDATES = 30;

    private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);
    private static final SecureRandom random = new SecureRandom();
    /** Palavras que não ajudam a achar nada. */
    private static final Set<String> STOPWORDS = Set.of("a", "o", "as", "os", "um", "uma", "de", "da", "do", "das",
            "dos", "e", "em", "no", "na", "nos", "nas", "que", "se", "para", "pra", "por", "com", "sem", "eu", "voce",
            "me", "meu", "minha", "isso", "isto", "esse", "essa", "este", "esta", "qual", "quais", "como", "onde",
            "quando", "foi", "era", "ser", "ter", "tem", "ao", "aos", "mais", "menos", "muito", "zordon", "hoje",
            "ontem", "anteontem", "semana", "passada", "sobre", "lembra", "lembre", "sabe");

    private final Connection db;
    private final Clock clock;
    private final Embedder embedder;
    private final int schemaVersion;
    private final List<String> migrations;

    public SqliteMemoryStore(Path file, Clock clock) {
        this(file, clock, null);
    }

    public SqliteMemoryStore(Path file, Clock clock, Embedder embedder) {
        this(file, clock, embedder, MIGRATIONS);
    }

    /** Para os testes de migração. */
    SqliteMemoryStore(Path file, Clock clock, Embedder embedder, List<String> migrations) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.embedder = embedder;
        this.migrations = List.copyOf(migrations);
        Connection connection = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            this.db = connection;
            try (Statement pragma = db.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA busy_timeout=5000");
            }
            this.schemaVersion = migrate(file);
        } catch (SQLException | IOException | RuntimeException e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // já estamos falhando; o motivo que importa é o de cima
                }
            }
            if (e instanceof IllegalStateException clear) {
                throw clear;
            }
            throw new IllegalStateException("memória não abriu em " + file + ": " + e.getMessage(), e);
        }
    }

    // migração --------------------------------------------------------------

    private int migrate(Path file) throws SQLException, IOException {
        try (Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int current = 0;
        try (Statement statement = db.createStatement();
                ResultSet row = statement.executeQuery("SELECT version FROM schema_version")) {
            if (row.next()) {
                current = row.getInt(1);
            }
        }
        int target = migrations.size();
        if (current > target) {
            throw new IllegalStateException("o banco de memória é da versão " + current + ", este Zordon conhece até "
                    + target);
        }
        if (current == target) {
            return current;
        }
        if (current > 0) {
            backup(file, current);
        }
        for (int version = current + 1; version <= target; version++) {
            String name = migrations.get(version - 1);
            List<String> statements = statements(resource("migrations/" + name));
            db.setAutoCommit(false);
            try (Statement statement = db.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
                statement.execute("DELETE FROM schema_version");
                statement.execute("INSERT INTO schema_version (version) VALUES (" + version + ")");
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw new IllegalStateException("migração " + name + " falhou; nada foi alterado: " + e.getMessage(), e);
            } finally {
                db.setAutoCommit(true);
            }
            log.info("memória migrada para a versão {} ({})", version, name);
        }
        return target;
    }

    /** Cópia consistente antes de migrar. Nunca sobrescreve uma cópia anterior. */
    private void backup(Path file, int version) throws SQLException {
        Path copy = file.resolveSibling(file.getFileName() + ".bak." + version);
        if (Files.exists(copy)) {
            copy = file.resolveSibling(file.getFileName() + ".bak." + version + "-" + clock.millis());
        }
        try (Statement statement = db.createStatement()) {
            statement.execute("VACUUM INTO '" + copy.toAbsolutePath().toString().replace("'", "''") + "'");
        }
        log.info("cópia da memória antes de migrar: {}", copy);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = SqliteMemoryStore.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("migração ausente do empacotamento: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Divide o script em comandos; o corpo de um gatilho vai inteiro até o {@code END;}. */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean trigger = false;
        for (String raw : script.split("\n")) {
            String line = raw.contains("--") ? raw.substring(0, raw.indexOf("--")) : raw;
            if (line.isBlank()) {
                continue;
            }
            current.append(line).append('\n');
            String trimmed = line.strip();
            if (trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE TRIGGER")) {
                trigger = true;
            }
            boolean ends = trigger ? trimmed.equalsIgnoreCase("END;") : trimmed.endsWith(";");
            if (ends) {
                out.add(current.toString().strip());
                current.setLength(0);
                trigger = false;
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().strip());
        }
        return out;
    }

    // conversa ----------------------------------------------------------------

    @Override
    public synchronized void session(String sessionId, String title, Instant startedAt) {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO session (id, title, started_at, last_active_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET title = COALESCE(excluded.title, session.title)")) {
            insert.setString(1, sessionId);
            insert.setString(2, title);
            insert.setString(3, startedAt.toString());
            insert.setString(4, startedAt.toString());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure("sessão", e);
        }
    }

    @Override
    public synchronized void message(String sessionId, String turnId, String role, String content, Instant ts) {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO message (session_id, turn_id, role, content, ts) VALUES (?, ?, ?, ?, ?)");
                PreparedStatement touch = db.prepareStatement(
                        "UPDATE session SET last_active_at = ? WHERE id = ?")) {
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
            throw failure("mensagem", e);
        }
    }

    @Override
    public synchronized List<StoredLine> history(String sessionId, int limit) {
        List<StoredLine> lines = lines("SELECT session_id, turn_id, role, content, ts FROM message WHERE session_id = ? "
                + "ORDER BY id DESC LIMIT " + Math.max(1, limit), sessionId);
        java.util.Collections.reverse(lines);
        return lines;
    }

    @Override
    public synchronized List<StoredLine> turn(String turnId) {
        return lines("SELECT session_id, turn_id, role, content, ts FROM message WHERE turn_id = ? ORDER BY id", turnId);
    }

    private List<StoredLine> lines(String sql, String param) {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            query.setString(1, param);
            List<StoredLine> out = new ArrayList<>();
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    out.add(new StoredLine(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                            Instant.parse(row.getString(5))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("histórico", e);
        }
    }

    // fatos -------------------------------------------------------------------

    @Override
    public synchronized Fact remember(NewFact fact) {
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
        String id = "f_" + Long.toString(now.toEpochMilli(), 36) + Long.toString(random.nextLong() & 0xffffffL, 36);
        try {
            db.setAutoCommit(false);
            try (PreparedStatement insert = db.prepareStatement(
                    "INSERT INTO fact (id, kind, subject, content, normalized, confidence, observed_at, expires_at, "
                            + "provenance, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    PreparedStatement supersede = db.prepareStatement(
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
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failure("fato", e);
        }
        Fact stored = one("SELECT * FROM fact WHERE id = ?", id);
        log.info("fato {} gravado ({}, {})", id, fact.kind(), fact.source());
        if (embedder != null) {
            embedder.indexed(stored);
        }
        return stored;
    }

    @Override
    public synchronized List<MemoryHit> search(RecallQuery query) {
        Instant now = clock.instant();
        List<List<String>> rankings = new ArrayList<>();
        String match = ftsQuery(query.text());
        StringBuilder filter = new StringBuilder(" AND f.superseded_by IS NULL AND (f.expires_at IS NULL OR f.expires_at > ?)");
        List<String> params = new ArrayList<>(List.of(now.toString()));
        if (!query.kinds().isEmpty()) {
            filter.append(" AND f.kind IN (").append(query.kinds().stream().map(kind -> "?")
                    .collect(Collectors.joining(","))).append(')');
            query.kinds().stream().map(Enum::name).sorted().forEach(params::add);
        }
        if (query.since() != null) {
            filter.append(" AND f.observed_at >= ?");
            params.add(query.since().toString());
        }
        if (query.until() != null) {
            filter.append(" AND f.observed_at < ?");
            params.add(query.until().toString());
        }
        if (!match.isEmpty()) {
            List<String> lexical = new ArrayList<>();
            List<String> all = new ArrayList<>(List.of(match));
            all.addAll(params);
            ids("SELECT f.id FROM fact_fts JOIN fact f ON f.rowid = fact_fts.rowid WHERE fact_fts MATCH ?" + filter
                    + " ORDER BY bm25(fact_fts) LIMIT " + CANDIDATES, all, lexical);
            rankings.add(lexical);
        }
        boolean windowed = query.since() != null || query.until() != null;
        if (windowed || (match.isEmpty() && !query.kinds().isEmpty())) {
            // Janela ou tipo sem palavra útil: os mais recentes dentro do filtro são uma lista também.
            List<String> recent = new ArrayList<>();
            ids("SELECT f.id FROM fact f WHERE 1 = 1" + filter + " ORDER BY f.observed_at DESC LIMIT " + CANDIDATES,
                    params, recent);
            rankings.add(recent);
        }
        if (embedder != null && !query.text().isBlank()) {
            rankings.add(embedder.nearest(query.text(), CANDIDATES));
        }
        Map<String, Double> fused = new HashMap<>();
        for (List<String> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                fused.merge(ranking.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
        List<MemoryHit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> entry : fused.entrySet()) {
            Fact fact = one("SELECT * FROM fact WHERE id = ?", entry.getKey());
            if (fact == null || !fact.active(now) || !query.kinds().isEmpty() && !query.kinds().contains(fact.kind())
                    || query.since() != null && fact.observedAt().isBefore(query.since())
                    || query.until() != null && !fact.observedAt().isBefore(query.until())) {
                continue;   // o embedder não sabe de filtro nem de fato esquecido
            }
            hits.add(new MemoryHit(fact, entry.getValue() * weight(fact, now)));
        }
        hits.sort(Comparator.comparingDouble(MemoryHit::score).reversed().thenComparing(hit -> hit.fact().id()));
        return List.copyOf(hits.subList(0, Math.min(query.limit(), hits.size())));
    }

    /** Confiança, recência e acessos (Memória §4). */
    static double weight(Fact fact, Instant now) {
        double confidence = 0.5 + 0.5 * fact.confidence();
        Duration halfLife = fact.kind().halfLife();
        double recency = 1.0;
        if (halfLife != null) {
            double age = Math.max(0, Duration.between(fact.observedAt(), now).toSeconds());
            recency = Math.pow(0.5, age / halfLife.toSeconds());
        }
        return confidence * recency * (1 + 0.1 * Math.log(1 + fact.accessCount()));
    }

    /** Palavras úteis viram termos com prefixo, unidos por OR; o resto é ignorado. */
    static String ftsQuery(String text) {
        List<String> terms = new ArrayList<>();
        for (String word : TimeWindows.plain(text).split("[^a-z0-9]+")) {
            if (word.length() < 2 || STOPWORDS.contains(word)) {
                continue;
            }
            // Um prefixo curto pega as flexões: "trabalhamos" acha "trabalhou".
            String term = word.length() >= 6 ? word.substring(0, Math.max(5, word.length() - 4)) : word;
            String quoted = "\"" + term + "\"" + (word.length() >= 4 ? "*" : "");
            if (!terms.contains(quoted)) {
                terms.add(quoted);
            }
        }
        return String.join(" OR ", terms);
    }

    @Override
    public synchronized List<Fact> facts(FactKind kind, String subject, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM fact WHERE superseded_by IS NULL AND (expires_at IS NULL OR expires_at > ?)");
        List<String> params = new ArrayList<>(List.of(clock.instant().toString()));
        if (kind != null) {
            sql.append(" AND kind = ?");
            params.add(kind.name());
        }
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND lower(subject) = lower(?)");
            params.add(subject);
        }
        sql.append(" ORDER BY observed_at DESC, id LIMIT ").append(Math.max(1, Math.min(limit, 500)));
        return many(sql.toString(), params);
    }

    @Override
    public synchronized void touched(Collection<String> factIds) {
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE fact SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ?")) {
            for (String id : factIds) {
                update.setString(1, clock.instant().toString());
                update.setString(2, id);
                update.addBatch();
            }
            update.executeBatch();
        } catch (SQLException e) {
            throw failure("acesso", e);
        }
    }

    @Override
    public synchronized boolean forget(String factId) {
        try (PreparedStatement delete = db.prepareStatement("DELETE FROM fact WHERE id = ?")) {
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
            throw failure("esquecer", e);
        }
    }

    @Override
    public synchronized int forgetSubject(String subject) {
        List<String> ids = new ArrayList<>();
        ids("SELECT id FROM fact WHERE lower(subject) = lower(?)", List.of(subject), ids);
        int count = 0;
        for (String id : ids) {
            count += forget(id) ? 1 : 0;
        }
        return count;
    }

    // destilação ------------------------------------------------------------

    @Override
    public synchronized void enqueueDistill(String turnId, String sessionId, boolean tainted, Instant now) {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT OR IGNORE INTO distill_queue (turn_id, session_id, tainted, created_at, next_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, turnId);
            insert.setString(2, sessionId);
            insert.setInt(3, tainted ? 1 : 0);
            insert.setString(4, now.toString());
            insert.setString(5, now.toString());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure("fila de destilação", e);
        }
    }

    @Override
    public synchronized List<DistillJob> dueDistill(Instant now, int limit) {
        try (PreparedStatement query = db.prepareStatement(
                "SELECT turn_id, session_id, tainted, attempts FROM distill_queue WHERE state = 'pending' "
                        + "AND next_at <= ? ORDER BY next_at LIMIT ?")) {
            query.setString(1, now.toString());
            query.setInt(2, limit);
            List<DistillJob> out = new ArrayList<>();
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    out.add(new DistillJob(row.getString(1), row.getString(2), row.getInt(3) == 1, row.getInt(4)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("fila de destilação", e);
        }
    }

    @Override
    public synchronized void distilled(String turnId) {
        update("UPDATE distill_queue SET state = 'done', attempts = attempts + 1 WHERE turn_id = ?", turnId);
    }

    @Override
    public synchronized void distillFailed(String turnId, String error, Instant nextAt, boolean giveUp) {
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE distill_queue SET attempts = attempts + 1, last_error = ?, next_at = ?, state = ? "
                        + "WHERE turn_id = ?")) {
            update.setString(1, error == null ? null : error.substring(0, Math.min(error.length(), 300)));
            update.setString(2, nextAt.toString());
            update.setString(3, giveUp ? "failed" : "pending");
            update.setString(4, turnId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("fila de destilação", e);
        }
    }

    @Override
    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", schemaVersion);
        out.put("facts", count("SELECT count(*) FROM fact WHERE superseded_by IS NULL"));
        out.put("sessions", count("SELECT count(*) FROM session"));
        out.put("distillPending", count("SELECT count(*) FROM distill_queue WHERE state = 'pending'"));
        out.put("distillFailed", count("SELECT count(*) FROM distill_queue WHERE state = 'failed'"));
        out.put("vectors", embedder != null);
        return out;
    }

    // tarefas (SPEC-023) ---------------------------------------------------

    private static final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public synchronized String createTask(String goal, String origin, List<PlanStep> steps) {
        Instant now = clock.instant();
        String id = "task_" + Long.toString(now.toEpochMilli(), 36) + Long.toString(random.nextLong() & 0xfffffL, 36);
        try {
            db.setAutoCommit(false);
            try (PreparedStatement task = db.prepareStatement(
                    "INSERT INTO task (id, goal, origin, state, created_at, updated_at) VALUES (?, ?, ?, 'planned', ?, ?)");
                    PreparedStatement step = db.prepareStatement(
                            "INSERT INTO task_step (task_id, id, ord, title, agent, depends_on, risk, done_when, state) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'planned')")) {
                task.setString(1, id);
                task.setString(2, goal);
                task.setString(3, origin);
                task.setString(4, now.toString());
                task.setString(5, now.toString());
                task.executeUpdate();
                for (int i = 0; i < steps.size(); i++) {
                    PlanStep plan = steps.get(i);
                    step.setString(1, id);
                    step.setString(2, plan.id());
                    step.setInt(3, i);
                    step.setString(4, plan.title());
                    step.setString(5, plan.agent());
                    step.setString(6, json.writeValueAsString(plan.dependsOn()));
                    step.setString(7, plan.risk());
                    step.setString(8, plan.doneWhen());
                    step.addBatch();
                }
                step.executeBatch();
                transitionRow(id, null, null, "planned", "plano com " + steps.size() + " etapa(s)", now);
                db.commit();
            } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
                db.rollback();
                throw new SQLException(e.getMessage(), e);
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failure("tarefa", e);
        }
        log.info("tarefa {} criada com {} etapa(s)", id, steps.size());
        return id;
    }

    @Override
    public synchronized void taskState(String taskId, String to, String reason) {
        String from = scalar("SELECT state FROM task WHERE id = ?", taskId);
        Instant now = clock.instant();
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE task SET state = ?, reason = ?, updated_at = ? WHERE id = ?")) {
            update.setString(1, to);
            update.setString(2, reason);
            update.setString(3, now.toString());
            update.setString(4, taskId);
            update.executeUpdate();
            transitionRow(taskId, null, from, to, reason, now);
        } catch (SQLException e) {
            throw failure("estado da tarefa", e);
        }
    }

    @Override
    public synchronized void stepState(String taskId, String stepId, String to, String reason) {
        Instant now = clock.instant();
        String from;
        try (PreparedStatement query = db.prepareStatement("SELECT state FROM task_step WHERE task_id = ? AND id = ?")) {
            query.setString(1, taskId);
            query.setString(2, stepId);
            try (ResultSet row = query.executeQuery()) {
                from = row.next() ? row.getString(1) : null;
            }
        } catch (SQLException e) {
            throw failure("estado da etapa", e);
        }
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE task_step SET state = ?, attempts = attempts + ? WHERE task_id = ? AND id = ?");
                PreparedStatement touch = db.prepareStatement("UPDATE task SET updated_at = ? WHERE id = ?")) {
            update.setString(1, to);
            update.setInt(2, "running".equals(to) ? 1 : 0);
            update.setString(3, taskId);
            update.setString(4, stepId);
            update.executeUpdate();
            touch.setString(1, now.toString());
            touch.setString(2, taskId);
            touch.executeUpdate();
            transitionRow(taskId, stepId, from, to, reason, now);
        } catch (SQLException e) {
            throw failure("estado da etapa", e);
        }
    }

    @Override
    public synchronized void stepResult(String taskId, String stepId, String resultJson) {
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE task_step SET result_json = ? WHERE task_id = ? AND id = ?")) {
            update.setString(1, resultJson);
            update.setString(2, taskId);
            update.setString(3, stepId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("resultado da etapa", e);
        }
    }

    @Override
    public synchronized void completeStep(String taskId, String stepId, String resultJson, String state, String reason) {
        try {
            db.setAutoCommit(false);
            try {
                stepResult(taskId, stepId, resultJson);
                stepState(taskId, stepId, state, reason);
                db.commit();
            } catch (RuntimeException | SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failure("conclusão da etapa", e);
        }
    }

    @Override
    public synchronized void verdict(Verdict verdict) {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO verdict (task_id, step_id, agent, model, kind, verdict, reason, tokens, duration_ms, at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, verdict.taskId());
            insert.setString(2, verdict.stepId());
            insert.setString(3, verdict.agent());
            insert.setString(4, verdict.model());
            insert.setString(5, verdict.kind());
            insert.setString(6, verdict.verdict());
            insert.setString(7, verdict.reason());
            insert.setLong(8, verdict.tokens());
            insert.setLong(9, verdict.durationMs());
            insert.setString(10, clock.instant().toString());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure("veredito", e);
        }
    }

    @Override
    public synchronized List<TaskView> tasks(int limit) {
        List<String> ids = new ArrayList<>();
        ids("SELECT id FROM task ORDER BY created_at DESC LIMIT " + Math.max(1, Math.min(limit, 200)), List.of(), ids);
        return ids.stream().map(this::task).flatMap(java.util.Optional::stream).toList();
    }

    @Override
    public synchronized java.util.Optional<TaskView> task(String taskId) {
        try (PreparedStatement query = db.prepareStatement(
                "SELECT id, goal, origin, state, reason, created_at, updated_at FROM task WHERE id = ?");
                PreparedStatement steps = db.prepareStatement(
                        "SELECT id, ord, title, agent, depends_on, risk, done_when, state, attempts, result_json "
                                + "FROM task_step WHERE task_id = ? ORDER BY ord")) {
            query.setString(1, taskId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return java.util.Optional.empty();
                }
                steps.setString(1, taskId);
                List<StepView> views = new ArrayList<>();
                try (ResultSet step = steps.executeQuery()) {
                    while (step.next()) {
                        views.add(new StepView(step.getString(1), step.getInt(2), step.getString(3), step.getString(4),
                                List.of(json.readValue(step.getString(5), String[].class)), step.getString(6),
                                step.getString(7), step.getString(8), step.getInt(9), step.getString(10)));
                    }
                }
                return java.util.Optional.of(new TaskView(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), Instant.parse(row.getString(6)),
                        Instant.parse(row.getString(7)), List.copyOf(views)));
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("memória (tarefa): " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<TaskView> interrupted() {
        List<String> ids = new ArrayList<>();
        ids("SELECT id FROM task WHERE state = 'running' ORDER BY created_at", List.of(), ids);
        return ids.stream().map(this::task).flatMap(java.util.Optional::stream).toList();
    }

    @Override
    public synchronized Map<String, Object> taskStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> byState = new LinkedHashMap<>();
        Map<String, Long> verdicts = new LinkedHashMap<>();
        try (Statement statement = db.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT state, count(*) FROM task GROUP BY state ORDER BY state")) {
                while (row.next()) {
                    byState.put(row.getString(1), row.getLong(2));
                }
            }
            String since = clock.instant().minus(Duration.ofDays(7)).toString().replace("'", "");
            try (ResultSet row = statement.executeQuery("SELECT verdict, count(*) FROM verdict WHERE at >= '" + since
                    + "' GROUP BY verdict ORDER BY verdict")) {
                while (row.next()) {
                    verdicts.put(row.getString(1), row.getLong(2));
                }
            }
        } catch (SQLException e) {
            throw failure("estatística de tarefas", e);
        }
        out.put("byState", byState);
        out.put("verdicts7d", verdicts);
        return out;
    }

    // automações (SPEC-025) ---------------------------------------------------

    @Override
    public synchronized long automationTokens(java.time.LocalDate day) {
        try (PreparedStatement query = db.prepareStatement("SELECT tokens FROM automation_budget WHERE day = ?")) {
            query.setString(1, day.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw failure("orçamento das automações", e);
        }
    }

    @Override
    public synchronized long reserveAutomationTokens(java.time.LocalDate day, long requested, long ceiling) {
        long reserved = Math.max(0, Math.min(requested, ceiling - automationTokens(day)));
        settleAutomationTokens(day, 0, reserved);
        return reserved;
    }

    @Override
    public synchronized void settleAutomationTokens(java.time.LocalDate day, long reserved, long used) {
        try (PreparedStatement update = db.prepareStatement("INSERT INTO automation_budget(day, tokens) VALUES (?, ?) "
                + "ON CONFLICT(day) DO UPDATE SET tokens = MAX(0, automation_budget.tokens + excluded.tokens)")) {
            update.setString(1, day.toString());
            update.setLong(2, Math.max(0, used) - Math.max(0, reserved));
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("orçamento das automações", e);
        }
    }

    @Override
    public synchronized State automationState(String id) {
        try (PreparedStatement query = db.prepareStatement(
                "SELECT last_fired_at, fired, failures, disabled, reason FROM automation_state WHERE id = ?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return State.fresh(id);
                }
                String last = row.getString(1);
                return new State(id, last == null ? null : Instant.parse(last), row.getInt(2), row.getInt(3),
                        row.getInt(4) == 1, row.getString(5));
            }
        } catch (SQLException e) {
            throw failure("estado da automação", e);
        }
    }

    @Override
    public synchronized void automationFired(String id, Instant at) {
        upsertAutomation(id);
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE automation_state SET last_fired_at = ?, fired = fired + 1, updated_at = ? WHERE id = ?")) {
            update.setString(1, at.toString());
            update.setString(2, clock.instant().toString());
            update.setString(3, id);
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("disparo da automação", e);
        }
    }

    @Override
    public synchronized int automationFinished(String id, boolean ok) {
        upsertAutomation(id);
        try (PreparedStatement update = db.prepareStatement(ok
                ? "UPDATE automation_state SET failures = 0, updated_at = ? WHERE id = ?"
                : "UPDATE automation_state SET failures = failures + 1, updated_at = ? WHERE id = ?")) {
            update.setString(1, clock.instant().toString());
            update.setString(2, id);
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("desfecho da automação", e);
        }
        return automationState(id).failures();
    }

    @Override
    public synchronized void automationDisabled(String id, boolean disabled, String reason) {
        upsertAutomation(id);
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE automation_state SET disabled = ?, reason = ?, failures = CASE WHEN ? = 0 THEN 0 ELSE failures END, "
                        + "updated_at = ? WHERE id = ?")) {
            update.setInt(1, disabled ? 1 : 0);
            update.setString(2, reason);
            update.setInt(3, disabled ? 1 : 0);
            update.setString(4, clock.instant().toString());
            update.setString(5, id);
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("automação desativada", e);
        }
    }

    private void upsertAutomation(String id) {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT OR IGNORE INTO automation_state (id, updated_at) VALUES (?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, clock.instant().toString());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure("estado da automação", e);
        }
    }

    // achados da defesa (SPEC-026) ------------------------------------------

    @Override
    public synchronized void finding(FindingRow finding) {
        try (PreparedStatement upsert = db.prepareStatement(
                "INSERT INTO finding (id, severity, detector, subject_kind, subject_id, title, rationale, "
                        + "signals_json, count, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET severity = excluded.severity, detector = excluded.detector, "
                        + "title = excluded.title, rationale = excluded.rationale, signals_json = excluded.signals_json, "
                        + "count = excluded.count, last_seen = excluded.last_seen")) {
            upsert.setString(1, finding.id());
            upsert.setString(2, finding.severity());
            upsert.setString(3, finding.detector());
            upsert.setString(4, finding.subjectKind());
            upsert.setString(5, finding.subjectId());
            upsert.setString(6, finding.title());
            upsert.setString(7, finding.rationale());
            upsert.setString(8, finding.signalsJson());
            upsert.setInt(9, finding.count());
            upsert.setString(10, finding.firstSeen().toString());
            upsert.setString(11, finding.lastSeen().toString());
            upsert.executeUpdate();
        } catch (SQLException e) {
            throw failure("achado", e);
        }
    }

    @Override
    public synchronized List<FindingRow> findings(Instant since, List<String> severities, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, severity, detector, subject_kind, subject_id, title, "
                + "rationale, signals_json, count, first_seen, last_seen, acknowledged_at FROM finding WHERE 1 = 1");
        List<String> params = new ArrayList<>();
        if (since != null) {
            sql.append(" AND last_seen >= ?");
            params.add(since.toString());
        }
        if (severities != null && !severities.isEmpty()) {
            sql.append(" AND severity IN (").append(severities.stream().map(item -> "?")
                    .collect(Collectors.joining(","))).append(')');
            params.addAll(severities);
        }
        sql.append(" ORDER BY last_seen DESC LIMIT ").append(Math.max(1, Math.min(limit, 500)));
        try (PreparedStatement query = db.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                query.setString(i + 1, params.get(i));
            }
            List<FindingRow> out = new ArrayList<>();
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    String acknowledged = row.getString(12);
                    out.add(new FindingRow(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                            row.getString(5), row.getString(6), row.getString(7), row.getString(8), row.getInt(9),
                            Instant.parse(row.getString(10)), Instant.parse(row.getString(11)),
                            acknowledged == null ? null : Instant.parse(acknowledged)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failure("achados", e);
        }
    }

    @Override
    public synchronized boolean acknowledgeFinding(String id, Instant at) {
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE finding SET acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL")) {
            update.setString(1, at.toString());
            update.setString(2, id);
            return update.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("achado confirmado", e);
        }
    }

    @Override
    public synchronized Map<String, Object> findingStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        String since = clock.instant().minus(Duration.ofHours(24)).toString();
        try (PreparedStatement query = db.prepareStatement(
                "SELECT severity, count(*) FROM finding WHERE last_seen >= ? GROUP BY severity ORDER BY severity")) {
            query.setString(1, since);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    out.put(row.getString(1), row.getLong(2));
                }
            }
        } catch (SQLException e) {
            throw failure("achados", e);
        }
        return Map.of("last24h", out);
    }

    // eventos de segurança (SPEC-027) ---------------------------------------

    @Override
    public synchronized String securityEvent(SecurityEventRow event) {
        String previous = lastSecurityHash();
        String payload = String.join("\u001f", event.eventId(), event.ts().toString(), event.severity(),
                event.detector(), event.subject(), String.valueOf(event.findingId()), event.proposed(),
                event.executed(), event.outcome(), event.authorization(), String.valueOf(event.rollbackAvailable()),
                String.valueOf(event.userMessageId()), previous);
        String hash = sha256(payload);
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO security_event (event_id, ts, severity, detector, subject, finding_id, proposed, "
                        + "executed, outcome, authorization_kind, rollback_available, user_message_id, prev_hash, hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, event.eventId());
            insert.setString(2, event.ts().toString());
            insert.setString(3, event.severity());
            insert.setString(4, event.detector());
            insert.setString(5, event.subject());
            insert.setString(6, event.findingId());
            insert.setString(7, event.proposed());
            insert.setString(8, event.executed());
            insert.setString(9, event.outcome());
            insert.setString(10, event.authorization());
            insert.setInt(11, event.rollbackAvailable() ? 1 : 0);
            insert.setString(12, event.userMessageId());
            insert.setString(13, previous);
            insert.setString(14, hash);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure("evento de segurança", e);
        }
        return hash;
    }

    @Override
    public synchronized List<Map<String, Object>> securityEvents(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement(
                "SELECT event_id, ts, severity, detector, subject, finding_id, proposed, executed, outcome, "
                        + "authorization_kind, rollback_available, user_message_id FROM security_event "
                        + "ORDER BY id DESC LIMIT ?")) {
            query.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("eventId", row.getString(1));
                    event.put("ts", row.getString(2));
                    event.put("severity", row.getString(3));
                    event.put("detector", row.getString(4));
                    event.put("subject", row.getString(5));
                    event.put("findingId", row.getString(6));
                    event.put("proposed", row.getString(7));
                    event.put("executed", row.getString(8));
                    event.put("outcome", row.getString(9));
                    event.put("authorization", row.getString(10));
                    event.put("rollbackAvailable", row.getInt(11) == 1);
                    event.put("userMessageId", row.getString(12));
                    out.add(event);
                }
            }
        } catch (SQLException e) {
            throw failure("eventos de segurança", e);
        }
        return out;
    }

    @Override
    public synchronized boolean securityChainOk() {
        try (Statement statement = db.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT event_id, ts, severity, detector, subject, finding_id, proposed, executed, outcome, "
                                + "authorization_kind, rollback_available, user_message_id, prev_hash, hash "
                                + "FROM security_event ORDER BY id")) {
            String previous = "";
            while (row.next()) {
                String payload = String.join("\u001f", row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), String.valueOf(row.getString(6)), row.getString(7),
                        row.getString(8), row.getString(9), row.getString(10),
                        String.valueOf(row.getInt(11) == 1), String.valueOf(row.getString(12)), previous);
                if (!previous.equals(row.getString(13)) || !sha256(payload).equals(row.getString(14))) {
                    return false;
                }
                previous = row.getString(14);
            }
            return true;
        } catch (SQLException e) {
            throw failure("cadeia de segurança", e);
        }
    }

    private String lastSecurityHash() {
        try (Statement statement = db.createStatement();
                ResultSet row = statement.executeQuery("SELECT hash FROM security_event ORDER BY id DESC LIMIT 1")) {
            return row.next() ? row.getString(1) : "";
        } catch (SQLException e) {
            throw failure("cadeia de segurança", e);
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // conhecimento (SPEC-028) ------------------------------------------------

    @Override
    public synchronized int indexDocument(String path, String hash, List<KnowledgeStore.Chunk> chunks) {
        try (PreparedStatement seen = db.prepareStatement("SELECT file_hash FROM doc_file WHERE path = ?")) {
            seen.setString(1, path);
            try (ResultSet row = seen.executeQuery()) {
                if (row.next() && hash.equals(row.getString(1))) {
                    return 0;   // mesmo arquivo: nada a reescrever
                }
            }
        } catch (SQLException e) {
            throw failure("índice de documentos", e);
        }
        Instant now = clock.instant();
        try {
            db.setAutoCommit(false);
            try (PreparedStatement clear = db.prepareStatement("DELETE FROM doc_chunk WHERE path = ?");
                    PreparedStatement insert = db.prepareStatement(
                            "INSERT INTO doc_chunk (path, file_hash, heading, ord, level, text, indexed_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)");
                    PreparedStatement file = db.prepareStatement(
                            "INSERT INTO doc_file (path, file_hash, chunks, indexed_at) VALUES (?, ?, ?, ?) "
                                    + "ON CONFLICT(path) DO UPDATE SET file_hash = excluded.file_hash, "
                                    + "chunks = excluded.chunks, indexed_at = excluded.indexed_at")) {
                clear.setString(1, path);
                clear.executeUpdate();
                for (int i = 0; i < chunks.size(); i++) {
                    KnowledgeStore.Chunk chunk = chunks.get(i);
                    insert.setString(1, path);
                    insert.setString(2, hash);
                    insert.setString(3, chunk.heading());
                    insert.setInt(4, i);
                    insert.setString(5, chunk.level());
                    insert.setString(6, chunk.text());
                    insert.setString(7, now.toString());
                    insert.addBatch();
                }
                insert.executeBatch();
                file.setString(1, path);
                file.setString(2, hash);
                file.setInt(3, chunks.size());
                file.setString(4, now.toString());
                file.executeUpdate();
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failure("índice de documentos", e);
        }
        return chunks.size();
    }

    @Override
    public synchronized int forgetDocumentsOutside(List<String> paths) {
        List<String> gone = new ArrayList<>();
        indexedDocuments().keySet().stream().filter(path -> !paths.contains(path)).forEach(gone::add);
        for (String path : gone) {
            update("DELETE FROM doc_chunk WHERE path = ?", path);
            update("DELETE FROM doc_file WHERE path = ?", path);
        }
        return gone.size();
    }

    @Override
    public synchronized List<KnowledgeStore.Hit> searchDocs(String query, int limit) {
        String match = ftsQuery(query);
        if (match.isEmpty()) {
            return List.of();
        }
        List<KnowledgeStore.Hit> out = new ArrayList<>();
        try (PreparedStatement search = db.prepareStatement(
                "SELECT c.path, c.heading, c.text, bm25(doc_fts) FROM doc_fts JOIN doc_chunk c ON c.id = doc_fts.rowid "
                        + "WHERE doc_fts MATCH ? ORDER BY bm25(doc_fts) LIMIT ?")) {
            search.setString(1, match);
            search.setInt(2, Math.max(1, Math.min(limit, 50)));
            try (ResultSet row = search.executeQuery()) {
                while (row.next()) {
                    out.add(new KnowledgeStore.Hit(row.getString(1), row.getString(2), row.getString(3),
                            -row.getDouble(4)));
                }
            }
        } catch (SQLException e) {
            log.debug("busca na documentação falhou: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public synchronized Map<String, String> indexedDocuments() {
        Map<String, String> out = new LinkedHashMap<>();
        try (Statement statement = db.createStatement();
                ResultSet row = statement.executeQuery("SELECT path, file_hash FROM doc_file ORDER BY path")) {
            while (row.next()) {
                out.put(row.getString(1), row.getString(2));
            }
        } catch (SQLException e) {
            throw failure("índice de documentos", e);
        }
        return out;
    }

    @Override
    public synchronized Map<String, Object> knowledgeStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", count("SELECT count(*) FROM doc_file"));
        out.put("chunks", count("SELECT count(*) FROM doc_chunk"));
        try (Statement statement = db.createStatement();
                ResultSet row = statement.executeQuery("SELECT max(indexed_at) FROM doc_file")) {
            out.put("indexedAt", row.next() && row.getString(1) != null ? row.getString(1) : "nunca");
        } catch (SQLException e) {
            throw failure("índice de documentos", e);
        }
        return out;
    }

    // uso de tokens (SPEC-029) -----------------------------------------------

    @Override
    public synchronized void recordUsage(java.time.LocalDate day, String provider, String model, String actor,
            long input, long output) {
        try (PreparedStatement upsert = db.prepareStatement(
                "INSERT INTO usage_daily (day, provider, model, actor, input_tokens, output_tokens, calls) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1) ON CONFLICT(day, provider, model, actor) DO UPDATE SET "
                        + "input_tokens = input_tokens + excluded.input_tokens, "
                        + "output_tokens = output_tokens + excluded.output_tokens, calls = calls + 1")) {
            upsert.setString(1, day.toString());
            upsert.setString(2, provider == null ? "?" : provider);
            upsert.setString(3, model == null ? "?" : model);
            upsert.setString(4, actor == null ? "?" : actor);
            upsert.setLong(5, Math.max(0, input));
            upsert.setLong(6, Math.max(0, output));
            upsert.executeUpdate();
        } catch (SQLException e) {
            throw failure("uso", e);
        }
    }

    @Override
    public synchronized Map<String, Object> usageSummary(int days) {
        String since = clock.instant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .minusDays(Math.max(0, days - 1)).toString();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> byActor = new LinkedHashMap<>();
        Map<String, Object> byDay = new LinkedHashMap<>();
        long input = 0;
        long output = 0;
        long calls = 0;
        try (PreparedStatement query = db.prepareStatement(
                "SELECT day, actor, sum(input_tokens), sum(output_tokens), sum(calls) FROM usage_daily "
                        + "WHERE day >= ? GROUP BY day, actor ORDER BY day DESC, actor")) {
            query.setString(1, since);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    long in = row.getLong(3);
                    long ou = row.getLong(4);
                    input += in;
                    output += ou;
                    calls += row.getLong(5);
                    byActor.merge(row.getString(2), in + ou, (a, b) -> ((Number) a).longValue()
                            + ((Number) b).longValue());
                    byDay.merge(row.getString(1), in + ou, (a, b) -> ((Number) a).longValue()
                            + ((Number) b).longValue());
                }
            }
        } catch (SQLException e) {
            throw failure("uso", e);
        }
        out.put("since", since);
        out.put("inputTokens", input);
        out.put("outputTokens", output);
        out.put("calls", calls);
        out.put("byActor", byActor);
        out.put("byDay", byDay);
        return out;
    }

    private void transitionRow(String taskId, String stepId, String from, String to, String reason, Instant at)
            throws SQLException {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO task_transition (task_id, step_id, from_state, to_state, reason, at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, taskId);
            insert.setString(2, stepId);
            insert.setString(3, from);
            insert.setString(4, to);
            insert.setString(5, reason);
            insert.setString(6, at.toString());
            insert.executeUpdate();
        }
        log.info("tarefa {}{}: {} → {}{}", taskId, stepId == null ? "" : "/" + stepId, from, to,
                reason == null ? "" : " (" + reason + ")");
    }

    private String scalar(String sql, String param) {
        List<String> out = new ArrayList<>();
        ids(sql, List.of(param), out);
        return out.isEmpty() ? null : out.getFirst();
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    // apoio -------------------------------------------------------------------

    static String normalize(String text) {
        return TimeWindows.plain(text).replaceAll("\\s+", " ").replaceAll("[\\s.!;,]+$", "").strip();
    }

    private void update(String sql, String param) {
        try (PreparedStatement update = db.prepareStatement(sql)) {
            update.setString(1, param);
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("atualização", e);
        }
    }

    private long count(String sql) {
        try (Statement statement = db.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : 0;
        } catch (SQLException e) {
            throw failure("contagem", e);
        }
    }

    private void ids(String sql, List<String> params, List<String> out) {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                query.setString(i + 1, params.get(i));
            }
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    out.add(row.getString(1));
                }
            }
        } catch (SQLException e) {
            // Consulta FTS malformada não derruba a conversa: devolve nada.
            log.debug("busca na memória falhou: {}", e.getMessage());
        }
    }

    private Fact one(String sql, String... params) {
        List<Fact> found = many(sql, List.of(params));
        return found.isEmpty() ? null : found.getFirst();
    }

    private List<Fact> many(String sql, List<String> params) {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                query.setString(i + 1, params.get(i));
            }
            List<Fact> out = new ArrayList<>();
            try (ResultSet row = query.executeQuery()) {
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
            throw failure("leitura de fatos", e);
        }
    }

    private static IllegalStateException failure(String what, SQLException e) {
        return new IllegalStateException("memória (" + what + "): " + e.getMessage(), e);
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            log.debug("memória fechada com erro: {}", e.getMessage());
        }
    }
}
