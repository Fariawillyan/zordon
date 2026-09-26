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
 * O Conhecimento (SPEC-030): quanta documentação está indexada, de onde, e se o
 * índice envelheceu.
 *
 * <p>Índice velho é dito, nunca escondido (SPEC-028 §3) — e o botão que resolve
 * fica ao lado da frase que avisa.
 */
@Spec("SPEC-030")
final class KnowledgeView extends DestinationPage {

    private final DesktopState state;
    private final ShellActions actions;

    KnowledgeView(DesktopState state, ShellActions actions) {
        super("RECURSOS", "Conhecimento", actions.memory()::loadKnowledge);
        this.state = state;
        this.actions = actions;
        setId("knowledge-view");
        state.resources().knowledgeProperty().addListener((observable, before, now) -> render());
        render();
    }

    @Override
    void render() {
        Map<String, Object> status = state.resources().knowledgeProperty().get();
        if (status.isEmpty()) {
            show(Cards.section("Índice", new VBox(8,
                    muted("Ainda sem resposta do núcleo sobre o índice de documentação."))));
            return;
        }
        long chunks = number(status.get("chunks"));
        VBox numbers = new VBox(8, Inspector.rows(
                "Arquivos indexados", String.valueOf(number(status.get("files"))),
                "Pedaços", String.valueOf(chunks),
                "Indexado em", text(status, "indexedAt")));
        if (chunks == 0) {
            numbers.getChildren().add(muted("Não há índice ainda: use Reindexar para o Zordon ler a documentação"
                    + " antes de responder sobre o projeto."));
        }
        numbers.getChildren().add(button("knowledge-reindex", "Reindexar", actions.memory()::reindexKnowledge));

        VBox roots = rows(paths(status.get("roots")), "Nenhuma raiz configurada — veja [rag] roots no config.toml.",
                root -> wrapped(text(root, "path")));
        roots.setId("knowledge-roots");

        VBox sections = new VBox(16, Cards.section("Índice", numbers),
                Cards.section("Raízes", roots));
        long stale = number(status.get("staleFiles"));
        if (stale > 0) {
            Label warning = wrapped(stale + " arquivo(s) mudaram desde a última indexação: o Zordon vai dizer isso"
                    + " nas respostas até você reindexar.");
            warning.getStyleClass().add("warning-text");
            sections.getChildren().add(Cards.section("Índice velho", new VBox(8, warning)));
        }
        if (status.get("problems") instanceof List<?> problems && !problems.isEmpty()) {
            VBox list = new VBox(6);
            problems.forEach(problem -> list.getChildren().add(wrapped(String.valueOf(problem))));
            sections.getChildren().add(Cards.section("Não deu para ler", list));
        }
        show(sections);
    }

    /** As raízes chegam como texto; a lista genérica pede mapa. */
    private static List<Map<String, Object>> paths(Object value) {
        return value instanceof List<?> found
                ? found.stream().map(item -> Map.<String, Object>of("path", String.valueOf(item))).toList()
                : List.of();
    }

    static long number(Object value) {
        return value instanceof Number found ? found.longValue() : 0;
    }
}
