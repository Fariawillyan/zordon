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
 * Os servidores MCP (SPEC-030): quem está ligado, o que trouxe, e o que mudou.
 *
 * <p>Servidor que muda a superfície fica bloqueado até alguém aprovar aqui
 * (SPEC-020 CA-3) — e "aqui" é só esta janela, nunca o modelo.
 */
@Spec("SPEC-030")
final class McpView extends DestinationPage {

    private final DesktopState state;
    private final ShellActions actions;

    McpView(DesktopState state, ShellActions actions) {
        super("RECURSOS", "MCP", actions.data()::loadMcp);
        this.state = state;
        this.actions = actions;
        setId("mcp-view");
        repaintOn(state.resources().mcpServers());
        render();
    }

    @Override
    void render() {
        Label hint = muted("Um servidor MCP traz ferramentas novas sem mudar o Zordon. Elas passam pelo mesmo"
                + " motor de permissão, com o risco do piso declarado no config.toml.");
        VBox list = rows(List.copyOf(state.resources().mcpServers()),
                "Nenhum servidor MCP declarado — veja [[mcp.server]] no config.toml.", this::serverRow);
        list.setId("mcp-list");
        show(Cards.section("Servidores (" + state.resources().mcpServers().size() + ")", new VBox(8, hint, list)));
    }

    private javafx.scene.Node serverRow(Map<String, Object> server) {
        String name = text(server, "name");
        Label line = wrapped(name + " · " + stateLabel(text(server, "state"))
                + "\nFerramentas: " + SkillsView.tools(server)
                + (text(server, "error").isEmpty() ? "" : "\nÚltimo erro: " + text(server, "error")));
        if (Boolean.TRUE.equals(server.get("drift"))) {
            line.getStyleClass().add("warning-text");
            return actionRow(line, button("mcp-approve-" + name, "Aprovar a superfície nova",
                    () -> actions.data().approveMcp(name)));
        }
        line.getStyleClass().add("settings-row");
        return line;
    }

    /** O estado em palavras: "drift" não diz a ninguém o que aconteceu. */
    static String stateLabel(String wire) {
        return switch (wire) {
            case "ready" -> "pronto";
            case "drift" -> "mudou de superfície — bloqueado até você aprovar";
            case "failed" -> "falhou";
            case "starting" -> "iniciando";
            case "isolated" -> "isolado pela defesa";
            case "stopped" -> "parado";
            default -> wire.isBlank() ? "estado desconhecido" : wire;
        };
    }

    static List<String> names(List<Map<String, Object>> servers) {
        return servers.stream().map(server -> String.valueOf(server.get("name"))).toList();
    }
}
