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
package zordon.desktop.ui;

import java.util.Map;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;

/**
 * O Sistema (SPEC-030): CPU, memória, disco e rede, e o monitor de contêineres.
 *
 * <p>A amostragem é adaptativa (SPEC-024): 1 Hz com a tela aberta, 0,2 Hz em
 * repouso. A tela diz em que ritmo está, para o número não parecer travado.
 */
@Spec("SPEC-030")
final class SystemView extends DestinationPage {

    /** O núcleo manda -1 quando não conseguiu medir; -1% não é medida, é ausência. */
    private static final String UNMEASURED = "não medido";

    private final DesktopState state;

    SystemView(DesktopState state, ShellActions actions) {
        super("OPERAÇÃO", "Sistema", actions::loadSystem);
        this.state = state;
        setId("system-view");
        state.systemMetricsProperty().addListener((observable, before, now) -> render());
        render();
    }

    @Override
    void render() {
        Map<String, Object> metrics = state.systemMetricsProperty().get();
        if (metrics.isEmpty()) {
            show(Cards.section("Máquina", new VBox(8, muted("Ainda sem medida do núcleo."))));
            return;
        }
        VBox machine = new VBox(8, Inspector.rows(
                "CPU", percent(metrics.get("cpu")),
                "Carga", value(metrics.get("load")),
                "Memória", memory(metrics),
                "Disco", disk(metrics),
                "Rede", value(metrics.get("netRxKbps")) + " kb/s entrando · "
                        + value(metrics.get("netTxKbps")) + " kb/s saindo",
                "Medido em", text(metrics, "sampledAt"),
                "Ritmo", value(metrics.get("rateHz")) + " Hz"));

        VBox monitor = new VBox(8);
        if (metrics.get("docker") instanceof Map<?, ?> docker) {
            Object reason = docker.get("reason");
            Object dockerState = docker.get("state");
            monitor.getChildren().add(wrapped("Docker: " + (dockerState == null ? "?" : dockerState)
                    + (reason == null ? "" : " — " + reason)));
        } else {
            monitor.getChildren().add(muted("Sem informação do monitor de contêineres."));
        }
        show(Cards.section("Máquina", machine), Cards.section("Monitor", monitor));
    }

    private static String percent(Object value) {
        double number = value instanceof Number found ? found.doubleValue() : -1;
        return number < 0 ? UNMEASURED : String.format(java.util.Locale.ROOT, "%.1f%%", number);
    }

    private static String value(Object raw) {
        double number = raw instanceof Number found ? found.doubleValue() : -1;
        return number < 0 ? UNMEASURED : String.format(java.util.Locale.ROOT, "%.1f", number);
    }

    private static String memory(Map<String, Object> metrics) {
        long used = KnowledgeView.number(metrics.get("memUsedMb"));
        long total = KnowledgeView.number(metrics.get("memTotalMb"));
        return total <= 0 ? UNMEASURED
                : used + " MB de " + total + " MB (" + percent(metrics.get("memPercent")) + ")";
    }

    private static String disk(Map<String, Object> metrics) {
        double total = metrics.get("diskTotalGb") instanceof Number found ? found.doubleValue() : -1;
        return total <= 0 ? UNMEASURED
                : value(metrics.get("diskUsedGb")) + " GB de " + value(metrics.get("diskTotalGb"))
                        + " GB (" + percent(metrics.get("diskPercent")) + ")";
    }
}
