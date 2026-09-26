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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.trace.Spec;
import zordon.core.event.ZordonEventBus;
import zordon.core.notify.NotificationCenter;
import zordon.core.tools.SkillRuntime;
import zordon.defense.DetectionEngine;
import zordon.defense.Detector;
import zordon.defense.Detectors;
import zordon.defense.Finding;
import zordon.defense.Observation;
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

    /** Para onde vai um achado: gravado, avisado ao usuário e publicado para as telas. */
    public record Outlets(FindingStore store, NotificationCenter notifications, ZordonEventBus bus) {}

    private final DetectionEngine engine;
    private final FindingReports reports;
    private final Clock clock;

    public DefenseService(Outlets outlets, Redactor redactor, Clock clock, LongSupplier nanos) {
        this.clock = clock;
        this.reports = new FindingReports(outlets, clock);
        List<Detector> detectors = new ArrayList<>(Detectors.ring1(redactor, nanos));
        detectors.addAll(Detectors.ring2());
        detectors.addAll(Detectors.integrity());
        this.engine = new DetectionEngine(detectors, clock, reports::found);
    }

    /** Quem responde ao achado (SPEC-027). Sem isso, a defesa só observa. */
    public void respondWith(BiConsumer<Finding, String> engine) {
        reports.respondWith(engine);
    }

    /** Observações de fora do caminho das ferramentas (o Anel 2, SPEC-027). */
    public void observe(Observation observation) {
        engine.observe(observation);
    }

    // observações do caminho das ferramentas ---------------------------------

    @Override
    public void call(ActionDescriptor action, Principal actor, String turnId, Decision decision,
            Set<Effect> declared, boolean tainted) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paths", action.touchedPaths().stream().map(path -> path.toWsl()).toList().toString());
        data.put("args", new TreeMap<>(action.args()).toString());
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

    // achados ----------------------------------------------------------------

    public List<Map<String, Object>> findings(Instant since, List<String> severities, int limit) {
        return reports.findings(since, severities, limit);
    }

    public boolean acknowledge(String findingId) {
        engine.acknowledge(findingId);
        return reports.acknowledge(findingId);
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>(reports.stats());
        out.put("open", engine.open().size());
        return out;
    }

}
