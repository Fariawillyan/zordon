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
 * As Skills (SPEC-030): o que o modelo pode pedir, com o risco de cada uma.
 *
 * <p>É a lista de ferramentas do {@code SkillRuntime}, e não um catálogo de
 * promessas: o que está aqui é o que o motor de permissão aceita avaliar.
 */
@Spec("SPEC-030")
final class SkillsView extends DestinationPage {

    private final DesktopState state;

    SkillsView(DesktopState state, ShellActions actions) {
        super("RECURSOS", "Skills", actions::loadSkills);
        this.state = state;
        setId("skills-view");
        repaintOn(state.skills());
        render();
    }

    @Override
    void render() {
        Label hint = muted("Cada Skill tem um risco base. GREEN roda direto; YELLOW pede confirmação;"
                + " RED é negado sem perguntar. Nada aqui contorna o motor de permissão.");
        VBox list = rows(List.copyOf(state.skills()), "Nenhuma ferramenta disponível — o núcleo não respondeu ainda.",
                skill -> {
                    String effects = skill.get("effects") instanceof List<?> found && !found.isEmpty()
                            ? found.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                            : "nenhum efeito declarado";
                    Label line = wrapped(text(skill, "name") + " · " + risk(text(skill, "risk"))
                            + "\n" + text(skill, "description") + "\nEfeitos: " + effects);
                    line.getStyleClass().add("settings-row");
                    return line;
                });
        list.setId("skill-list");
        show(Cards.section("Ferramentas (" + state.skills().size() + ")", new VBox(8, hint, list)));
    }

    /** O risco em palavras: "green" não diz nada a quem não leu a SPEC. */
    static String risk(String wire) {
        return switch (wire) {
            case "green" -> "verde (roda direto)";
            case "yellow" -> "amarelo (pede confirmação)";
            case "red" -> "vermelho (negado)";
            default -> wire.isBlank() ? "risco não declarado" : wire;
        };
    }

    static String tools(Map<String, Object> row) {
        return row.get("tools") instanceof List<?> tools && !tools.isEmpty()
                ? tools.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                : "nenhuma ferramenta";
    }
}
