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

import java.util.List;
import java.util.Map;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;

/**
 * As Automações (SPEC-030): o que roda sozinho, e o que está esperando você.
 *
 * <p>Proposta não vira automação sem aprovação nesta tela (SPEC-025 §3). Só
 * GREEN executa sem perguntar; o resto continua parando para confirmar.
 */
@Spec("SPEC-030")
final class AutomationsView extends DestinationPage {

    private final DesktopState state;
    private final ShellActions actions;

    AutomationsView(DesktopState state, ShellActions actions) {
        super("RECURSOS", "Automações", actions::loadAutomations);
        this.state = state;
        this.actions = actions;
        setId("automations-view");
        repaintOn(state.automations(), state.automationProposals());
        render();
    }

    @Override
    void render() {
        VBox proposals = rows(List.copyOf(state.automationProposals()), "Nada esperando aprovação.", proposal -> {
            String id = text(proposal, "proposalId");
            Label line = wrapped(text(proposal, "goal")
                    + (text(proposal, "rationale").isEmpty() ? "" : "\nPor quê: " + text(proposal, "rationale"))
                    + (text(proposal, "summary").isEmpty() ? "" : "\n" + text(proposal, "summary")));
            return actionRow(line,
                    button("automation-approve-" + id, "Aprovar", () -> actions.approveAutomation(id)),
                    button("automation-reject-" + id, "Recusar", () -> actions.rejectAutomation(id)));
        });
        proposals.setId("automation-proposals");

        VBox active = rows(List.copyOf(state.automations()),
                "Nenhuma automação ativa. Peça uma na conversa: \"todo dia às 9h, …\".", this::automationRow);
        active.setId("automation-list");

        VBox sections = new VBox(16);
        if (!state.automationProposals().isEmpty()) {
            sections.getChildren().add(Cards.section(
                    "Esperando você (" + state.automationProposals().size() + ")", proposals));
        }
        sections.getChildren().add(Cards.section("Ativas (" + state.automations().size() + ")", active));
        show(sections);
    }

    private javafx.scene.Node automationRow(Map<String, Object> automation) {
        String id = text(automation, "id");
        boolean enabled = !Boolean.FALSE.equals(automation.get("enabled"));
        Label line = wrapped(text(automation, "name").isEmpty() ? id : text(automation, "name")
                + (enabled ? "" : " · desligada")
                + (Boolean.TRUE.equals(automation.get("running")) ? " · rodando agora" : "")
                + (text(automation, "lastFiredAt").isEmpty() ? "\nAinda não disparou."
                        : "\nÚltima vez: " + text(automation, "lastFiredAt")));
        line.getStyleClass().add("settings-row");
        return actionRow(line,
                button("automation-toggle-" + id, enabled ? "Desligar" : "Ligar",
                        () -> actions.enableAutomation(id, !enabled)),
                button("automation-run-" + id, "Rodar agora", () -> actions.runAutomation(id)));
    }
}
