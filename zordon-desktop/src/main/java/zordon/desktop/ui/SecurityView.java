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
 * A Segurança (SPEC-030): o que a defesa viu, o que ela fez, e o que ainda
 * bloqueia.
 *
 * <p>Toda ação executada tem uma mensagem ao usuário — é invariante do
 * {@code SecurityEvent} (SPEC-027). Esta tela é onde essa mensagem vira lista.
 */
@Spec("SPEC-030")
final class SecurityView extends DestinationPage {

    private final DesktopState state;
    private final ShellActions actions;

    SecurityView(DesktopState state, ShellActions actions) {
        super("OPERAÇÃO", "Segurança", () -> {
            actions.security().loadFindings();
            actions.security().loadSecurityEvents();
        });
        this.state = state;
        this.actions = actions;
        setId("security-view");
        repaintOn(state.security().findings(), state.security().breakers(), state.security().securityEvents());
        state.security().lockdownProperty().addListener((observable, before, now) -> render());
        render();
    }

    @Override
    void render() {
        // O kill switch, os achados e os disjuntores já existiam na aba "Segurança"
        // dos Ajustes (hoje em SecurityCards), mais completos do que uma segunda
        // versão aqui seria — então esta tela os reusa e acrescenta só o que
        // faltava: o que a defesa fez.
        VBox events = rows(List.copyOf(state.security().securityEvents()), "Nenhuma ação de defesa registrada.",
                SecurityView::eventRow);
        events.setId("security-events");

        show(SecurityCards.security(state, actions),
                OppressorCard.oppressor(state, actions),
                SecurityCards.notifications(state, actions),
                Cards.section("O que a defesa fez", events),
                SecurityCards.quarantine(state, actions));
    }

    private static javafx.scene.Node eventRow(Map<String, Object> event) {
        String executed = text(event, "executed");
        Label line = wrapped(text(event, "ts") + " · " + text(event, "detector")
                + "\nProposto: " + orNone(text(event, "proposed"))
                + " · Executado: " + orNone(executed)
                + (text(event, "outcome").isEmpty() ? "" : " · " + text(event, "outcome"))
                + (Boolean.TRUE.equals(event.get("rollbackAvailable")) ? "\nReversível." : ""));
        line.getStyleClass().add("settings-row");
        return line;
    }

    private static String orNone(String value) {
        return value.isEmpty() || "NONE".equals(value) ? "nada" : value;
    }

}
