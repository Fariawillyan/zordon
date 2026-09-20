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
 * A Memória (SPEC-030): o que o Zordon lembra, de onde veio, e o esquecer.
 *
 * <p>Esquecer de verdade só acontece aqui (SPEC-021 §3): o modelo pode pedir para
 * lembrar, nunca para apagar.
 */
@Spec("SPEC-030")
final class MemoryView extends DestinationPage {

    private final DesktopState state;
    private final ShellActions actions;

    MemoryView(DesktopState state, ShellActions actions) {
        super("RECURSOS", "Memória", actions::loadMemory);
        this.state = state;
        this.actions = actions;
        setId("memory-view");
        repaintOn(state.memoryFacts());
        render();
    }

    @Override
    void render() {
        Label hint = muted("Cada fato guarda de onde veio. Esquecer é definitivo, e só esta janela faz isso —"
                + " o modelo pede para lembrar, nunca para apagar.");
        VBox list = rows(List.copyOf(state.memoryFacts()),
                "O Zordon ainda não lembra de nada. Diga \"lembre que …\" na conversa.", this::factRow);
        list.setId("memory-list");
        show(Cards.section("Fatos (" + state.memoryFacts().size() + ")", new VBox(8, hint, list)));
    }

    private javafx.scene.Node factRow(Map<String, Object> fact) {
        String id = text(fact, "id");
        Label line = wrapped(text(fact, "content")
                + "\n" + VoiceSettingsView.kindLabel(text(fact, "kind"))
                + (text(fact, "observedAt").isEmpty() ? "" : " · " + text(fact, "observedAt"))
                + (text(fact, "source").isEmpty() ? "" : " · origem: " + text(fact, "source")));
        line.getStyleClass().add("settings-row");
        return actionRow(line, button("memory-forget-" + id, "Esquecer", () -> actions.forgetFact(id)));
    }
}
