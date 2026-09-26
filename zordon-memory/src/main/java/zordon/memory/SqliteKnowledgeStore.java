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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base de conhecimento indexada da documentação (SPEC-028).
 *
 * <p>Parte do {@link ZordonDatabase}: divisão feita na SPEC-035, quando os sete
 * contratos deixaram de morar numa classe só. O banco e o monitor continuam
 * únicos — o que se separou foi a responsabilidade.
 */
public final class SqliteKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteKnowledgeStore.class);

    private final Sql sql;
    private final Clock clock;

    SqliteKnowledgeStore(ZordonDatabase database) {
        this.sql = database.sql();
        this.clock = database.clock();
    }

    // conhecimento (SPEC-028) ------------------------------------------------

    @Override
    public int indexDocument(String path, String hash, List<KnowledgeStore.Chunk> chunks) {
        synchronized (sql) {
            try (PreparedStatement seen = sql.prepare("SELECT file_hash FROM doc_file WHERE path = ?")) {
                seen.setString(1, path);
                try (ResultSet row = seen.executeQuery()) {
                    if (row.next() && hash.equals(row.getString(1))) {
                        return 0;   // mesmo arquivo: nada a reescrever
                    }
                }
            } catch (SQLException e) {
                throw Sql.failure("índice de documentos", e);
            }
            Instant now = clock.instant();
            try {
                sql.connection().setAutoCommit(false);
                try (PreparedStatement clear = sql.prepare("DELETE FROM doc_chunk WHERE path = ?");
                        PreparedStatement insert = sql.prepare(
                                "INSERT INTO doc_chunk (path, file_hash, heading, ord, level, text, indexed_at) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?)");
                        PreparedStatement file = sql.prepare(
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
                    sql.connection().commit();
                } catch (SQLException e) {
                    sql.connection().rollback();
                    throw e;
                } finally {
                    sql.connection().setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw Sql.failure("índice de documentos", e);
            }
            return chunks.size();
        }
    }

    @Override
    public int forgetDocumentsOutside(List<String> paths) {
        synchronized (sql) {
            List<String> gone = new ArrayList<>();
            indexedDocuments().keySet().stream().filter(path -> !paths.contains(path)).forEach(gone::add);
            for (String path : gone) {
                sql.update("DELETE FROM doc_chunk WHERE path = ?", path);
                sql.update("DELETE FROM doc_file WHERE path = ?", path);
            }
            return gone.size();
        }
    }

    @Override
    public List<KnowledgeStore.Hit> searchDocs(String query, int limit) {
        synchronized (sql) {
            String match = SqliteMemoryStore.ftsQuery(query);
            if (match.isEmpty()) {
                return List.of();
            }
            List<KnowledgeStore.Hit> out = new ArrayList<>();
            try (PreparedStatement search = sql.prepare(
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
    }

    @Override
    public Map<String, String> indexedDocuments() {
        synchronized (sql) {
            Map<String, String> out = new LinkedHashMap<>();
            try (Statement statement = sql.statement();
                    ResultSet row = statement.executeQuery("SELECT path, file_hash FROM doc_file ORDER BY path")) {
                while (row.next()) {
                    out.put(row.getString(1), row.getString(2));
                }
            } catch (SQLException e) {
                throw Sql.failure("índice de documentos", e);
            }
            return out;
        }
    }

    @Override
    public Map<String, Object> knowledgeStats() {
        synchronized (sql) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("files", sql.count("SELECT count(*) FROM doc_file"));
            out.put("chunks", sql.count("SELECT count(*) FROM doc_chunk"));
            try (Statement statement = sql.statement();
                    ResultSet row = statement.executeQuery("SELECT max(indexed_at) FROM doc_file")) {
                out.put("indexedAt", row.next() && row.getString(1) != null ? row.getString(1) : "nunca");
            } catch (SQLException e) {
                throw Sql.failure("índice de documentos", e);
            }
            return out;
        }
    }

}
