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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Texto determinístico dos achados de defesa. */
final class FindingText {

    private FindingText() {}

    static String rationale(List<Signal> signals, double weight) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        signals.forEach(signal -> counted.merge(signal.detectorId() + " (" + signal.kind() + ")", 1, Integer::sum));
        StringBuilder out = new StringBuilder();
        counted.forEach((name, times) -> out.append(out.isEmpty() ? "" : "; ").append(name)
                .append(times > 1 ? " ×" + times : ""));
        return out + ". Peso somado " + String.format(java.util.Locale.ROOT, "%.2f", weight)
                + " na janela de " + FindingWindow.SIZE.toSeconds() + " s.";
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
}
