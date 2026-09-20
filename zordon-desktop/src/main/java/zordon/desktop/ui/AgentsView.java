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
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;

/**
 * Os Agentes (SPEC-030): quem existe, com que teto, e o que ficou de fora.
 *
 * <p>Um agente é um arquivo TOML (SPEC-022). A tela mostra e explica; criar
 * continua sendo escrever o arquivo, e a tela diz onde.
 */
@Spec("SPEC-030")
final class AgentsView extends DestinationPage {

    private final DesktopState state;

    AgentsView(DesktopState state, ShellActions actions) {
        super("RECURSOS", "Agentes", actions::loadAgents);
        this.state = state;
        setId("agents-view");
        repaintOn(state.agents());
        render();
    }

    @Override
    void render() {
        List<java.util.Map<String, Object>> all = List.copyOf(state.agents());
        List<java.util.Map<String, Object>> loaded = all.stream().filter(a -> !a.containsKey("reason")).toList();
        List<java.util.Map<String, Object>> ignored = all.stream().filter(a -> a.containsKey("reason")).toList();

        VBox list = rows(loaded, "Nenhum agente disponível — o núcleo não respondeu ainda.", agent -> {
            Label line = wrapped(text(agent, "id") + " · teto " + SkillsView.risk(text(agent, "ceiling"))
                    + ("builtin".equals(text(agent, "source")) ? " · do Zordon" : " · seu")
                    + "\n" + text(agent, "description")
                    + "\nPapel do modelo: " + text(agent, "role"));
            line.getStyleClass().add("settings-row");
            return line;
        });
        list.setId("agent-list");

        Label hint = muted("Diga \"pergunta pro developer: …\" para escolher um agente."
                + " Para criar um, ponha um arquivo .toml em ~/.zordon/agents/ — ele vale sem reiniciar.");
        VBox sections = new VBox(16, Cards.section("Perfis (" + loaded.size() + ")", new VBox(8, hint, list)));

        if (!ignored.isEmpty()) {
            VBox problems = rows(ignored, "", agent -> {
                Label line = wrapped(text(agent, "file") + " — " + text(agent, "reason"));
                line.getStyleClass().add("warning-text");
                return line;
            });
            problems.setId("agent-ignored");
            sections.getChildren().add(Cards.section("Arquivos que não viraram agente", problems));
        }
        show(sections);
    }
}
