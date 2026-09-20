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
package zordon.core.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.Severity;
import zordon.api.trace.Spec;

/**
 * O único caminho de toda comunicação iniciada pelo Zordon
 * (docs/security/communication.md §2 e §8, SPEC-015).
 *
 * <p>Grava antes de sair: se o núcleo cair entre gravar e entregar, a mensagem
 * está na fila e sai na próxima conexão. CRITICAL e HIGH ficam pendentes até o
 * usuário confirmar; INFO e WARNING expiram em 24 h.
 */
@Spec("SPEC-015")
public final class NotificationCenter implements AutoCloseable {

    public static final Duration SHORT_LIVED = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(NotificationCenter.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final Connection db;
    private final Clock clock;
    private final Consumer<ZordonMessage> delivery;

    /** @param delivery publica o evento {@code SECURITY_NOTIFICATION}; chamada só depois de gravar */
    public NotificationCenter(Path file, Clock clock, Consumer<ZordonMessage> delivery) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS notification (
                          id TEXT PRIMARY KEY, ts TEXT NOT NULL, severity TEXT NOT NULL,
                          body TEXT NOT NULL, expires_at TEXT, acknowledged_at TEXT)""");
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("fila de notificações indisponível em " + file + ": " + e.getMessage(), e);
        }
    }

    /** Uma mensagem nova, com id e hora atribuídos aqui. */
    public ZordonMessage message(Severity severity, String channel, String title, String whatHappened,
            String whySuspicious, String detectedBy, String actionTaken, String affectedResource, boolean reversible,
            String currentState, List<String> options) {
        return new ZordonMessage("msg-" + UUID.randomUUID(), severity, channel, title, whatHappened, whySuspicious,
                detectedBy, actionTaken, affectedResource, reversible, currentState, options, null, clock.instant());
    }

    /** Grava e entrega. @return o id que liga a mensagem ao evento de segurança */
    public synchronized String publish(ZordonMessage message) {
        return publish(message, false);
    }

    /** Substitui a mesma mensagem agrupada, reabrindo-a se já foi lida. */
    public synchronized String publishGrouped(ZordonMessage message) {
        return publish(message, true);
    }

    private String publish(ZordonMessage message, boolean grouped) {
        Instant expires = message.severity().compareTo(Severity.HIGH) >= 0 ? null
                : message.ts().plus(SHORT_LIVED);
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO notification (id, ts, severity, body, expires_at) VALUES (?, ?, ?, ?, ?)"
                        + (grouped ? " ON CONFLICT(id) DO UPDATE SET ts = excluded.ts, severity = excluded.severity, "
                                + "body = excluded.body, expires_at = excluded.expires_at, acknowledged_at = NULL" : ""))) {
            insert.setString(1, message.id());
            insert.setString(2, message.ts().toString());
            insert.setString(3, message.severity().wire());
            insert.setString(4, json.writeValueAsString(message.payload()));
            insert.setString(5, expires == null ? null : expires.toString());
            insert.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("notificação não gravada: " + e.getMessage(), e);
        }
        log.info("notificação {} ({}): {}", message.id(), message.severity().wire(), message.title());
        delivery.accept(message);
        return message.id();
    }

    /** O usuário viu. @return falso se a mensagem não existe ou já estava confirmada */
    public synchronized boolean acknowledge(String id) {
        try (PreparedStatement update = db.prepareStatement(
                "UPDATE notification SET acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL")) {
            update.setString(1, clock.instant().toString());
            update.setString(2, id);
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("confirmação não gravada: " + e.getMessage(), e);
        }
    }

    /** Não confirmadas e não expiradas, da mais antiga para a mais nova. */
    public synchronized List<Map<String, Object>> pending() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement(
                "SELECT body FROM notification WHERE acknowledged_at IS NULL "
                        + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY ts, id")) {
            query.setString(1, clock.instant().toString());
            try (ResultSet r = query.executeQuery()) {
                while (r.next()) {
                    out.add(json.readValue(r.getString(1), new TypeReference<Map<String, Object>>() { }));
                }
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("fila de notificações: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            log.warn("fechando a fila de notificações: {}", e.getMessage());
        }
    }
}
