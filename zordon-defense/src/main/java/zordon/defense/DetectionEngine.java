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
package zordon.defense;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.Severity;
import zordon.api.trace.Spec;

/**
 * Observa, pontua e correlaciona (SPEC-026). Sinais do mesmo sujeito numa janela
 * viram um achado só: é a correlação que separa uma ferramenta útil de um gerador
 * de ruído (Defesa §4).
 */
@Spec("SPEC-026")
public final class DetectionEngine {

    static final Duration WINDOW = Duration.ofSeconds(60);

    /** Peso acumulado para cada severidade. */
    static Severity severity(double weight) {
        if (weight >= 1.0) {
            return Severity.CRITICAL;
        }
        if (weight >= 0.7) {
            return Severity.HIGH;
        }
        return weight >= 0.4 ? Severity.WARNING : Severity.INFO;
    }

    private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

    private final List<Detector> detectors;
    private final Clock clock;
    private final Consumer<Finding> onFinding;
    private final Map<String, Finding> open = new LinkedHashMap<>();

    public DetectionEngine(List<Detector> detectors, Clock clock, Consumer<Finding> onFinding) {
        this.detectors = List.copyOf(detectors);
        this.clock = clock;
        this.onFinding = onFinding;
    }

    /** No caminho quente: barato, e um detector que falha não derruba os outros. */
    public void observe(Observation observation) {
        for (Detector detector : detectors) {
            List<Signal> signals;
            try {
                signals = detector.inspect(observation);
            } catch (RuntimeException e) {
                log.warn("detector {} falhou: {}", detector.id(), e.toString());
                continue;
            }
            signals.forEach(signal -> correlate(observation.subject(), signal));
        }
    }

    private synchronized void correlate(Subject subject, Signal signal) {
        Instant now = clock.instant();
        String key = subject.toString();
        Finding current = open.get(key);
        if (current != null && Duration.between(current.lastSeen(), now).compareTo(WINDOW) > 0) {
            current = null;   // a janela fechou: o próximo sinal começa outro achado
        }
        List<Signal> signals = new ArrayList<>(current == null ? List.of() : current.signals());
        signals.add(signal);
        double weight = signals.stream().mapToDouble(Signal::weight).sum();
        Severity severity = severity(weight);
        String detector = signals.stream().map(Signal::detectorId).distinct().reduce((a, b) -> a + ", " + b).orElse("");
        Finding finding = new Finding(current == null ? "fnd-" + UUID.randomUUID() : current.id(), severity, detector,
                subject, title(subject, signals), rationale(signals, weight), signals, signals.size(),
                current == null ? now : current.firstSeen(), now);
        open.put(key, finding);
        log.info("achado {} · {} · {} (peso {})", finding.id(), severity.wire(), subject, String.format("%.2f", weight));
        try {
            onFinding.accept(finding);
        } catch (RuntimeException e) {
            log.warn("entrega do achado falhou: {}", e.getMessage());
        }
    }

    /** O motivo, escrito por código: é o que o usuário lê para decidir (Defesa §4). */
    static String rationale(List<Signal> signals, double weight) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        signals.forEach(signal -> counted.merge(signal.detectorId() + " (" + signal.kind() + ")", 1, Integer::sum));
        StringBuilder out = new StringBuilder();
        counted.forEach((name, times) -> out.append(out.isEmpty() ? "" : "; ").append(name)
                .append(times > 1 ? " ×" + times : ""));
        return out + ". Peso somado " + String.format(java.util.Locale.ROOT, "%.2f", weight)
                + " na janela de " + WINDOW.toSeconds() + " s.";
    }

    static String title(Subject subject, List<Signal> signals) {
        String first = signals.getLast().kind();
        return switch (subject.kind()) {
            case "agent" -> "Agente " + subject.id() + ": " + first;
            case "mcp" -> "Servidor MCP " + subject.id() + ": " + first;
            case "self" -> "Integridade do Zordon: " + first;
            case "turn" -> "Nesta conversa: " + first;
            case "process" -> "Processo " + subject.id() + ": " + first;
            case "file" -> "Arquivo " + subject.id() + ": " + first;
            default -> subject.id() + ": " + first;
        };
    }

    /** Os achados abertos, do mais novo para o mais antigo. */
    public synchronized List<Finding> open() {
        return open.values().stream().sorted(java.util.Comparator.comparing(Finding::lastSeen).reversed()).toList();
    }

    /** Fecha um achado: o usuário confirmou que viu. */
    public synchronized boolean acknowledge(String findingId) {
        return open.values().removeIf(finding -> finding.id().equals(findingId));
    }
}
