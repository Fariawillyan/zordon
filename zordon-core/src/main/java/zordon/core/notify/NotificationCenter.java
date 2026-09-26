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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.Severity;

/**
 * O único caminho de toda comunicação iniciada pelo Zordon
 * (docs/security/communication.md §2 e §8, SPEC-015).
 *
 * <p>Grava antes de sair: se o núcleo cair entre gravar e entregar, a mensagem
 * está na fila e sai na próxima conexão. CRITICAL e HIGH ficam pendentes até o
 * usuário confirmar; INFO e WARNING expiram em 24 h.
 */
public final class NotificationCenter implements AutoCloseable {

    /** Conteúdo de uma mensagem, agrupado para evitar chamadas ambíguas. */
    public record MessageFields(Severity severity, String channel, String title, String whatHappened,
            String whySuspicious, String detectedBy, String actionTaken, String affectedResource, boolean reversible,
            String currentState, List<String> options) {}

    public static final Duration SHORT_LIVED = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(NotificationCenter.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final NotificationStore store;
    private final Clock clock;
    private final Consumer<ZordonMessage> delivery;

    /** @param delivery publica o evento {@code SECURITY_NOTIFICATION}; chamada só depois de gravar */
    public NotificationCenter(Path file, Clock clock, Consumer<ZordonMessage> delivery) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS notification (
                          id TEXT PRIMARY KEY, ts TEXT NOT NULL, severity TEXT NOT NULL,
                          body TEXT NOT NULL, expires_at TEXT, acknowledged_at TEXT)""");
            }
            store = new NotificationStore(connection, json);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("fila de notificações indisponível em " + file + ": " + e.getMessage(), e);
        }
    }

    /** Uma mensagem nova, com id e hora atribuídos aqui. */
    public ZordonMessage message(MessageFields fields) {
        return new ZordonMessage("msg-" + UUID.randomUUID(), fields.severity(), fields.channel(), fields.title(),
                fields.whatHappened(), fields.whySuspicious(), fields.detectedBy(), fields.actionTaken(),
                fields.affectedResource(), fields.reversible(), fields.currentState(), fields.options(), null,
                clock.instant());
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
        store.save(message, expires, grouped);
        log.info("notificação {} ({}): {}", message.id(), message.severity().wire(), message.title());
        delivery.accept(message);
        return message.id();
    }

    /** O usuário viu. @return falso se a mensagem não existe ou já estava confirmada */
    public synchronized boolean acknowledge(String id) {
        return store.acknowledge(id, clock.instant());
    }

    /** Não confirmadas e não expiradas, da mais antiga para a mais nova. */
    public synchronized List<Map<String, Object>> pending() {
        return store.pending(clock.instant());
    }

    @Override
    public synchronized void close() {
        try {
            store.close();
        } catch (RuntimeException e) { log.warn("fechando a fila de notificações: {}", e.getMessage()); }
    }
}
