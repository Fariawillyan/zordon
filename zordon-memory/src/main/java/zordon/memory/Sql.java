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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O acesso ao SQLite: uma conexão, e o monitor que serializa quem a usa.
 *
 * <p>Existe separada porque a conexão é compartilhada por todos os contratos do
 * {@link ZordonDatabase} (SPEC-035): sem um dono único do {@code synchronized},
 * dois contratos escrevendo ao mesmo tempo na mesma conexão corromperiam o
 * estado. Todo acesso ao banco passa por aqui.
 */
final class Sql implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sql.class);

    private final Connection db;

    Sql(Connection db) {
        this.db = db;
    }

    /** A conexão crua, para quem precisa montar a própria consulta. */
    Connection connection() {
        return db;
    }

    PreparedStatement prepare(String sql) throws SQLException {
        return db.prepareStatement(sql);
    }

    Statement statement() throws SQLException {
        return db.createStatement();
    }

    void update(String sql, String param) {
        try (PreparedStatement update = db.prepareStatement(sql)) {
            update.setString(1, param);
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure("atualização", e);
        }
    }

    long count(String sql) {
        try (Statement statement = db.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : 0;
        } catch (SQLException e) {
            throw failure("contagem", e);
        }
    }

    void ids(String sql, List<String> params, List<String> out) {
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

    String scalar(String sql, String param) {
        List<String> out = new ArrayList<>();
        ids(sql, List.of(param), out);
        return out.isEmpty() ? null : out.getFirst();
    }

    static IllegalStateException failure(String what, SQLException e) {
        return new IllegalStateException("memória (" + what + "): " + e.getMessage(), e);
    }

    @Override
    public void close() {
        try {
            db.close();
        } catch (SQLException e) {
            log.debug("memória fechada com erro: {}", e.getMessage());
        }
    }
}
