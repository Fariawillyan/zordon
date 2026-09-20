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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.Severity;
import zordon.api.trace.Spec;
import zordon.core.event.ZordonEventBus;
import zordon.core.notify.NotificationCenter;
import zordon.core.tools.SkillRuntime;
import zordon.defense.DetectionEngine;
import zordon.defense.Detectors;
import zordon.defense.Finding;
import zordon.defense.Observation;
import zordon.defense.Signal;
import zordon.defense.Subject;
import zordon.memory.FindingStore;
import zordon.security.Redactor;

/**
 * Liga o motor de detecção ao que acontece no núcleo (SPEC-026): chamadas de
 * ferramenta, resultados, resposta do modelo, drift de MCP e integridade. Ele
 * observa e avisa; conter é da SPEC-027.
 */
@Spec("SPEC-026")
public final class DefenseService implements SkillRuntime.CallObserver {

    /** O resultado que vai ao detector é um trecho: evidência não é despejo. */
    static final int MAX_TEXT = 4_000;

    private static final Logger log = LoggerFactory.getLogger(DefenseService.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final DetectionEngine engine;
    private final FindingStore store;
    private final NotificationCenter notifications;
    private final ZordonEventBus bus;
    private final Clock clock;
    private volatile java.util.function.BiConsumer<Finding, String> responder = (finding, messageId) -> { };

    /** Quem responde ao achado (SPEC-027). Sem isso, a defesa só observa. */
    public void respondWith(java.util.function.BiConsumer<Finding, String> engine) {
        this.responder = java.util.Objects.requireNonNull(engine, "engine");
    }

    /** Observações de fora do caminho das ferramentas (o Anel 2, SPEC-027). */
    public void observe(zordon.defense.Observation observation) {
        engine.observe(observation);
    }

    public DefenseService(FindingStore store, NotificationCenter notifications, ZordonEventBus bus, Redactor redactor,
            Clock clock, java.util.function.LongSupplier nanos) {
        this.store = store;
        this.notifications = notifications;
        this.bus = bus;
        this.clock = clock;
        List<zordon.defense.Detector> detectors = new ArrayList<>(Detectors.ring1(redactor, nanos));
        detectors.addAll(Detectors.ring2());
        detectors.addAll(Detectors.integrity());
        this.engine = new DetectionEngine(detectors, clock, this::found);
    }

    // observações do caminho das ferramentas ---------------------------------

    @Override
    public void call(ActionDescriptor action, Principal actor, String turnId, Decision decision,
            Set<Effect> declared, boolean tainted) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paths", action.touchedPaths().stream().map(path -> path.toWsl()).toList().toString());
        data.put("args", new java.util.TreeMap<>(action.args()).toString());
        engine.observe(new Observation("tool.call", subjectOf(actor, turnId), actor.actor(), turnId, action.tool(),
                null, declared, action.effects(), decision.wire(), decision.reason(), tainted, data, clock.instant()));
    }

    @Override
    public void result(ActionDescriptor action, String turnId, String text, String status) {
        engine.observe(new Observation("tool.result", Subject.turn(turnId == null ? "avulso" : turnId), null, turnId,
                action.tool(), text == null ? null : text.substring(0, Math.min(MAX_TEXT, text.length())), null, null,
                status, null, false, Map.of(), clock.instant()));
    }

    /** A resposta que o modelo produziu (SPEC-026, `ai.output-anomaly`). */
    public void modelOutput(String turnId, String text) {
        engine.observe(new Observation("model.output", Subject.turn(turnId), null, turnId, null, text, null, null,
                null, null, false, Map.of(), clock.instant()));
    }

    /** Um servidor MCP mudou de superfície (SPEC-020). */
    public void mcpDrift(String server, String reason) {
        engine.observe(new Observation("mcp.drift", Subject.mcp(server), "mcp:" + server, null, null, null, null, null,
                null, reason, false, Map.of("servidor", server), clock.instant()));
    }

    /** A cadeia da auditoria não fecha: nada mais no sistema é confiável. */
    public void auditChainBroken(String reason) {
        engine.observe(new Observation("integrity.audit", Subject.self(), "self", null, null, null, null, null, null,
                reason, false, Map.of(), clock.instant()));
    }

    /** Um arquivo instalado ou de configuração mudou (SPEC-026, integridade). */
    public void integrityChanged(String kind, String path, String reason) {
        engine.observe(new Observation(kind, "integrity.self".equals(kind) ? Subject.self() : Subject.file(path),
                "self", null, null, null, null, null, null, reason, false, Map.of("arquivo", path), clock.instant()));
    }

    // achados ----------------------------------------------------------------

    private void found(Finding finding) {
        String messageId = null;
        try {
            store.finding(new FindingStore.FindingRow(finding.id(), finding.severity().wire(), finding.detector(),
                    finding.subject().kind(), finding.subject().id(), finding.title(), finding.rationale(),
                    json.writeValueAsString(finding.signals().stream().map(DefenseService::wire).toList()),
                    finding.count(), finding.firstSeen(), finding.lastSeen(), null));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("achado não gravado: {}", e.getMessage());
        }
        bus.publish(EventType.SECURITY_FINDING, Map.of("findingId", finding.id(), "severity",
                finding.severity().wire(), "detector", finding.detector(), "subject", finding.subject().toString(),
                "title", finding.title(), "rationale", finding.rationale()));
        if (finding.severity().compareTo(Severity.WARNING) >= 0 && finding.count() == countThreshold(finding)) {
            messageId = notifications.publish(notifications.message(finding.severity(), "AI_DEFENSE", finding.title(),
                    finding.rationale(),
                    "O detector é uma regra escrita, não um palpite: ele vê o que aconteceu no caminho mediado.",
                    "detecção " + finding.detector() + " (SPEC-026)",
                    "O que a defesa fez, se fez algo, está no histórico da tela de Segurança.",
                    finding.subject().toString(), true,
                    "Aberto, esperando sua leitura.",
                    List.of("Ver os detalhes na tela de Segurança", "Ignorar")));
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

    private static Subject subjectOf(Principal actor, String turnId) {
        String name = actor.actor();
        if (name.startsWith("agent:")) {
            return Subject.agent(name.substring("agent:".length()));
        }
        if (name.startsWith("automation:")) {
            return new Subject("automation", name.substring("automation:".length()));
        }
        return turnId == null ? Subject.actor(name) : Subject.turn(turnId);
    }

    public List<Map<String, Object>> findings(Instant since, List<String> severities, int limit) {
        return store.findings(since, severities, limit).stream().map(DefenseService::wire).toList();
    }

    public boolean acknowledge(String findingId) {
        engine.acknowledge(findingId);
        return store.acknowledgeFinding(findingId, clock.instant());
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>(store.findingStats());
        out.put("open", engine.open().size());
        return out;
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
