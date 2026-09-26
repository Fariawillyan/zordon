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
package zordon.core.defense;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.security.Severity;
import zordon.core.notify.NotificationCenter;
import zordon.defense.Finding;
import zordon.defense.Signal;
import zordon.memory.FindingStore;

/** O que acontece com um achado: gravado, publicado, avisado ao usuário e entregue a quem responde. */
final class FindingReports {

    private static final Logger log = LoggerFactory.getLogger(DefenseService.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final DefenseService.Outlets outlets;
    private final Clock clock;
    private volatile BiConsumer<Finding, String> responder = (finding, messageId) -> { };

    FindingReports(DefenseService.Outlets outlets, Clock clock) {
        this.outlets = outlets;
        this.clock = clock;
    }

    void respondWith(BiConsumer<Finding, String> engine) {
        this.responder = Objects.requireNonNull(engine, "engine");
    }

    void found(Finding finding) {
        String messageId = null;
        try {
            outlets.store().finding(new FindingStore.FindingRow(finding.id(), finding.severity().wire(), finding.detector(),
                    finding.subject().kind(), finding.subject().id(), finding.title(), finding.rationale(),
                    json.writeValueAsString(finding.signals().stream().map(FindingReports::wire).toList()),
                    finding.count(), finding.firstSeen(), finding.lastSeen(), null));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("achado não gravado: {}", e.getMessage());
        }
        outlets.bus().publish(EventType.SECURITY_FINDING, Map.of("findingId", finding.id(), "severity",
                finding.severity().wire(), "detector", finding.detector(), "subject", finding.subject().toString(),
                "title", finding.title(), "rationale", finding.rationale()));
        if (finding.severity().compareTo(Severity.WARNING) >= 0 && finding.count() == countThreshold(finding)) {
            messageId = outlets.notifications().publish(outlets.notifications().message(new NotificationCenter.MessageFields(
                    finding.severity(), "AI_DEFENSE", finding.title(), finding.rationale(),
                    "O detector é uma regra escrita, não um palpite: ele vê o que aconteceu no caminho mediado.",
                    "detecção " + finding.detector() + " (SPEC-026)",
                    "O que a defesa fez, se fez algo, está no histórico da tela de Segurança.",
                    finding.subject().toString(), true, "Aberto, esperando sua leitura.",
                    List.of("Ver os detalhes na tela de Segurança", "Ignorar"))));
        }
        try {
            // A resposta vem depois do aviso: é o aviso que o SecurityEvent referencia.
            responder.accept(finding, messageId);
        } catch (RuntimeException e) {
            log.warn("resposta ao achado {} falhou: {}", finding.id(), e.toString());
        }
    }

    /** Avisa na primeira vez e a cada dez sinais: anti-fadiga (Defesa §4). */
    private static int countThreshold(Finding finding) {
        return finding.count() <= 1 ? 1 : finding.count() % 10 == 0 ? finding.count() : -1;
    }

    static Map<String, Object> wire(Signal signal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detector", signal.detectorId());
        out.put("kind", signal.kind());
        out.put("weight", signal.weight());
        out.put("evidence", signal.evidence());
        out.put("ts", signal.ts().toString());
        return out;
    }

    List<Map<String, Object>> findings(Instant since, List<String> severities, int limit) {
        return outlets.store().findings(since, severities, limit).stream().map(FindingReports::wire).toList();
    }

    boolean acknowledge(String findingId) {
        return outlets.store().acknowledgeFinding(findingId, clock.instant());
    }

    Map<String, Object> stats() {
        return outlets.store().findingStats();
    }

    static Map<String, Object> wire(FindingStore.FindingRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findingId", row.id());
        out.put("severity", row.severity());
        out.put("detector", row.detector());
        out.put("subject", row.subjectKind() + ":" + row.subjectId());
        out.put("title", row.title());
        out.put("rationale", row.rationale());
        out.put("count", row.count());
        out.put("firstSeen", row.firstSeen().toString());
        out.put("lastSeen", row.lastSeen().toString());
        if (row.acknowledgedAt() != null) {
            out.put("acknowledgedAt", row.acknowledgedAt().toString());
        }
        out.put("signals", row.signalsJson());
        return out;
    }
}
