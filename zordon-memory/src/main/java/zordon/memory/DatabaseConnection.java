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

/** Abre a única conexão SQLite e aplica as migrações. */
final class DatabaseConnection {

    private DatabaseConnection() {}

    static Open open(Path file, Clock clock, List<String> migrations) {
        Connection connection = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA busy_timeout=5000");
            }
            return new Open(connection, Migrations.apply(connection, file, List.copyOf(migrations), clock));
        } catch (SQLException | IOException | RuntimeException e) {
            closeQuietly(connection);
            if (e instanceof IllegalStateException clear) {
                throw clear;
            }
            throw new IllegalStateException("memória não abriu em " + file + ": " + e.getMessage(), e);
        }
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

    record Open(Connection connection, int schemaVersion) {}
}
