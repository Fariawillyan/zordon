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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * O {@code ~/.zordon/zordon.db} e os contratos que ele serve (SPEC-021, ADR-0008).
 *
 * <p>Uma conexão só, serializada pelo {@link Sql}: é um assistente pessoal, não
 * um servidor. O que mudou na SPEC-035 foi a divisão — antes os sete contratos
 * viviam numa classe de 1.363 linhas com 48 métodos públicos, e cada
 * subsistema que tocava o banco arrastava os outros seis junto.
 *
 * <p>Cada acessor devolve a implementação daquele contrato. O banco continua um
 * só, e o monitor também: a divisão é de responsabilidade, não de conexão.
 */
public final class ZordonDatabase implements AutoCloseable {

    private final Sql sql;
    private final Clock clock;
    private final Embedder embedder;
    private final int schemaVersion;

    private final SqliteMemoryStore memory;
    private final SqliteTaskStore tasks;
    private final SqliteAutomationStateStore automations;
    private final SqliteFindingStore findings;
    private final SqliteSecurityEventStore securityEvents;
    private final SqliteKnowledgeStore knowledge;
    private final SqliteUsageStore usage;

    public ZordonDatabase(Path file, Clock clock) {
        this(file, clock, null);
    }

    public ZordonDatabase(Path file, Clock clock, Embedder embedder) {
        this(file, clock, embedder, Migrations.ALL);
    }

    /** Para os testes de migração. */
    ZordonDatabase(Path file, Clock clock, Embedder embedder, List<String> migrations) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.embedder = embedder;
        Connection connection = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA busy_timeout=5000");
            }
            this.schemaVersion = Migrations.apply(connection, file, List.copyOf(migrations), clock);
        } catch (SQLException | IOException | RuntimeException e) {
            closeQuietly(connection);
            if (e instanceof IllegalStateException clear) {
                throw clear;
            }
            throw new IllegalStateException("memória não abriu em " + file + ": " + e.getMessage(), e);
        }
        this.sql = new Sql(connection);
        this.memory = new SqliteMemoryStore(this);
        this.tasks = new SqliteTaskStore(this);
        this.automations = new SqliteAutomationStateStore(this);
        this.findings = new SqliteFindingStore(this);
        this.securityEvents = new SqliteSecurityEventStore(this);
        this.knowledge = new SqliteKnowledgeStore(this);
        this.usage = new SqliteUsageStore(this);
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // já estamos falhando; o motivo que importa é o de cima
        }
    }

    public SqliteMemoryStore memory() {
        return memory;
    }

    public SqliteTaskStore tasks() {
        return tasks;
    }

    public SqliteAutomationStateStore automations() {
        return automations;
    }

    public SqliteFindingStore findings() {
        return findings;
    }

    public SqliteSecurityEventStore securityEvents() {
        return securityEvents;
    }

    public SqliteKnowledgeStore knowledge() {
        return knowledge;
    }

    public SqliteUsageStore usage() {
        return usage;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    Sql sql() {
        return sql;
    }

    Clock clock() {
        return clock;
    }

    Embedder embedder() {
        return embedder;
    }

    @Override
    public void close() {
        sql.close();
    }
}
