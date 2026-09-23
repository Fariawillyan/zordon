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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O esquema do {@code zordon.db}: versão, migração e cópia antes de migrar.
 *
 * <p>Só para frente (Memória §7). Um banco de versão mais nova que este Zordon
 * não abre — degradar esquema em silêncio perderia dado.
 */
final class Migrations {

    /** As migrações, em ordem. */
    static final List<String> ALL = List.of("V001__memoria.sql", "V002__tarefas.sql", "V003__automacoes.sql",
            "V004__orcamento_automacoes.sql", "V005__achados.sql",
            "V006__eventos_de_seguranca.sql", "V007__conhecimento.sql", "V008__uso.sql");

    private static final Logger log = LoggerFactory.getLogger(Migrations.class);

    private Migrations() {}

    /** Aplica o que falta e devolve a versão em que o banco ficou. */
    static int apply(Connection db, Path file, List<String> migrations, Clock clock)
            throws SQLException, IOException {
        try (Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int current = current(db);
        int target = migrations.size();
        if (current > target) {
            throw new IllegalStateException("o banco de memória é da versão " + current + ", este Zordon conhece até "
                    + target);
        }
        if (current == target) {
            return current;
        }
        if (current > 0) {
            backup(db, file, current, clock);
        }
        for (int version = current + 1; version <= target; version++) {
            applyOne(db, migrations.get(version - 1), version);
        }
        return target;
    }

    private static int current(Connection db) throws SQLException {
        try (Statement statement = db.createStatement();
                ResultSet row = statement.executeQuery("SELECT version FROM schema_version")) {
            return row.next() ? row.getInt(1) : 0;
        }
    }

    private static void applyOne(Connection db, String name, int version) throws SQLException, IOException {
        List<String> script = statements(resource("migrations/" + name));
        db.setAutoCommit(false);
        try (Statement statement = db.createStatement()) {
            for (String sql : script) {
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

    /** Cópia consistente antes de migrar. Nunca sobrescreve uma cópia anterior. */
    private static void backup(Connection db, Path file, int version, Clock clock) throws SQLException {
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
        try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(name)) {
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
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("CREATE TRIGGER")) {
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
}
