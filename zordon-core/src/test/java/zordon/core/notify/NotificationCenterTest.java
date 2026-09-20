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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.Severity;
import zordon.api.trace.AcceptanceCriteria;

/** A fila durável de notificações (SPEC-015). */
class NotificationCenterTest {

    @TempDir
    Path dir;

    private static final class Moving extends Clock {
        Instant now = Instant.parse("2026-09-19T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Moving clock = new Moving();

    private NotificationCenter open(List<ZordonMessage> delivered) {
        return new NotificationCenter(dir.resolve("notifications.db"), clock, delivered::add);
    }

    private static ZordonMessage message(NotificationCenter center, Severity severity, String title) {
        return center.message(severity, "SYSTEM", title, "o que houve", "por que importa", "quem viu",
                "o que foi feito", "recurso", true, "estado agora", List.of("Ver na tela"));
    }

    @AcceptanceCriteria("SPEC-015/CA-3")
    @Test
    void gravaAntesDeEntregarESobreviveAoReinicio() {
        List<ZordonMessage> delivered = new CopyOnWriteArrayList<>();
        String id;
        try (NotificationCenter center = new NotificationCenter(dir.resolve("notifications.db"), clock, message -> {
            // Na hora da entrega, a mensagem já está gravada.
            delivered.add(message);
        })) {
            id = center.publish(message(center, Severity.CRITICAL, "Algo grave"));
            assertThat(center.pending()).extracting(m -> m.get("messageId")).containsExactly(id);
        }
        assertThat(delivered).singleElement().extracting(ZordonMessage::id).isEqualTo(id);
        try (NotificationCenter reopened = open(new CopyOnWriteArrayList<>())) {
            assertThat(reopened.pending()).singleElement().satisfies(m -> {
                assertThat(m).containsEntry("messageId", id).containsEntry("severity", "critical")
                        .containsEntry("whySuspicious", "por que importa");
            });
            assertThat(reopened.acknowledge(id)).isTrue();
            assertThat(reopened.acknowledge(id)).as("confirmar duas vezes não conta").isFalse();
            assertThat(reopened.pending()).isEmpty();
        }
    }

    @AcceptanceCriteria("SPEC-015/CA-4")
    @Test
    void criticalNaoExpiraEInfoEWarningExpiramEm24Horas() {
        try (NotificationCenter center = open(new CopyOnWriteArrayList<>())) {
            center.publish(message(center, Severity.INFO, "info"));
            center.publish(message(center, Severity.WARNING, "warning"));
            center.publish(message(center, Severity.CRITICAL, "critical"));
            assertThat(center.pending()).hasSize(3);

            clock.now = clock.now.plus(NotificationCenter.SHORT_LIVED).plus(Duration.ofMinutes(1));

            assertThat(center.pending()).extracting(m -> m.get("title")).containsExactly("critical");
        }
    }

    @AcceptanceCriteria("SPEC-015/CA-5")
    @Test
    void mensagemSemUmDosOitoCamposNaoExiste() {
        try (NotificationCenter center = open(new CopyOnWriteArrayList<>())) {
            assertThatThrownBy(() -> center.message(Severity.HIGH, "SECURITY", "t", "o que", "",
                    "quem", "ação", "recurso", true, "estado", List.of("x")))
                    .hasMessageContaining("whySuspicious");
            assertThatThrownBy(() -> center.message(Severity.HIGH, "SECURITY", "t", "o que", "por que",
                    "quem", " ", "recurso", true, "estado", List.of("x")))
                    .hasMessageContaining("actionTaken");
            assertThatThrownBy(() -> center.message(Severity.HIGH, "SECURITY", "t", "o que", "por que",
                    "quem", "ação", "recurso", true, "estado", List.of()))
                    .hasMessageContaining("options");
            Map<String, Object> payload = message(center, Severity.INFO, "ok").payload();
            assertThat(payload).containsKeys("whatHappened", "whySuspicious", "detectedBy", "actionTaken",
                    "affectedResource", "reversible", "currentState", "options");
        }
    }
}
