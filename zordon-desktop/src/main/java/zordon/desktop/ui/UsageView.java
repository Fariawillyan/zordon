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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;

/**
 * O Uso (SPEC-030): quantos tokens o dia custou, por ator e por dia.
 *
 * <p>Só o que cada resposta informou. Nada é estimado, nada sai da máquina
 * (SPEC-029 §3).
 */
@Spec("SPEC-030")
final class UsageView extends DestinationPage {

    private final DesktopState state;

    UsageView(DesktopState state, ShellActions actions) {
        super("OPERAÇÃO", "Uso", actions.data()::loadUsage);
        this.state = state;
        setId("usage-view");
        state.resources().usageSummaryProperty().addListener((observable, before, now) -> render());
        render();
    }

    @Override
    void render() {
        Map<String, Object> summary = state.resources().usageSummaryProperty().get();
        if (summary.isEmpty()) {
            show(Cards.section("Tokens", new VBox(8, muted("Ainda sem resposta do núcleo sobre o uso."))));
            return;
        }
        long input = KnowledgeView.number(summary.get("inputTokens"));
        long output = KnowledgeView.number(summary.get("outputTokens"));
        VBox totals = new VBox(8, Inspector.rows(
                "Desde", text(summary, "since"),
                "Entrada", thousands(input),
                "Saída", thousands(output),
                "Somados", thousands(input + output),
                "Chamadas", thousands(KnowledgeView.number(summary.get("calls")))));
        if (input + output == 0) {
            totals.getChildren().add(muted("Nenhum turno contabilizado ainda neste período."));
        }

        VBox byActor = rows(entries(summary.get("byActor")), "Nada por ator ainda.",
                row -> actionRow(wrapped(actorLabel(text(row, "key"))), wrapped(thousands(
                        KnowledgeView.number(row.get("value"))) + " tokens")));
        byActor.setId("usage-by-actor");
        VBox byDay = rows(entries(summary.get("byDay")), "Nada por dia ainda.",
                row -> actionRow(wrapped(text(row, "key")), wrapped(thousands(
                        KnowledgeView.number(row.get("value"))) + " tokens")));
        byDay.setId("usage-by-day");

        show(Cards.section("Tokens", totals),
                Cards.section("Por ator", byActor),
                Cards.section("Por dia", byDay));
    }

    /** "turno" e "agente:research" são os atores que o núcleo grava (SPEC-029 §3). */
    static String actorLabel(String actor) {
        if (actor.startsWith("agente:")) {
            return "Agente " + actor.substring("agente:".length());
        }
        return switch (actor) {
            case "turno" -> "Conversa";
            case "automacao" -> "Automação";
            case "destilacao" -> "Destilação da memória";
            default -> actor;
        };
    }

    private static List<Map<String, Object>> entries(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> out.add(Map.of("key", String.valueOf(key),
                    "value", item == null ? 0 : item)));
        }
        out.sort((a, b) -> Long.compare(KnowledgeView.number(b.get("value")),
                KnowledgeView.number(a.get("value"))));
        return out;
    }

    /** 1234567 vira "1.234.567": número grande sem separador não se lê. */
    static String thousands(long value) {
        return String.format(java.util.Locale.of("pt", "BR"), "%,d", value).replace(',', '.');
    }
}
