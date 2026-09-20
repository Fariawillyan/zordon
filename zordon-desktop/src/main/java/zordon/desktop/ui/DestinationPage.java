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
import java.util.function.Function;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;

/**
 * O que toda tela de destino tem em comum (SPEC-030): trilha, título, o botão de
 * atualizar e a carga pedida ao abrir — uma vez, não a cada pintura.
 *
 * <p>A tela não fala com o núcleo: ela pede ao {@link ShellActions}, que é quem
 * conhece o ZWP.
 */
@Spec("SPEC-030")
abstract class DestinationPage extends ScrollPane {

    private final VBox content = new VBox(16);
    private final String crumb;
    private final String titleText;
    private final Runnable load;
    private boolean everOpened;

    DestinationPage(String crumb, String titleText, Runnable load) {
        this.crumb = crumb;
        this.titleText = titleText;
        this.load = load;
        getStyleClass().add("page");
        setFitToWidth(true);
        content.setPadding(new Insets(20, 24, 24, 24));
        content.setMaxWidth(880);
        setContent(content);
    }

    /**
     * A tela apareceu. A carga sai só na primeira vez: abrir o desktop não pode
     * disparar nove chamadas ao núcleo (SPEC-030 CA-3).
     */
    final void opened() {
        if (!everOpened) {
            everOpened = true;
            load.run();
        }
    }

    /** Refaz o corpo. O cabeçalho é sempre o mesmo, então mora aqui. */
    final void show(Node... sections) {
        Label trail = new Label(crumb);
        trail.getStyleClass().add("crumb");
        Label title = new Label(titleText);
        title.getStyleClass().add("page-title");
        Button refresh = new Button("Atualizar");
        refresh.getStyleClass().add("button-secondary");
        refresh.setId("refresh-" + titleText.toLowerCase(java.util.Locale.ROOT)
                .replace("ç", "c").replace("õ", "o").replace("ó", "o").replace("é", "e").replace("ã", "a"));
        refresh.setOnAction(event -> load.run());
        content.getChildren().setAll(new VBox(4, trail, title), refresh);
        content.getChildren().addAll(sections);
    }

    /**
     * Uma lista de linhas, ou a frase que explica o vazio. Tabela vazia não diz
     * nada; frase diz (SPEC-030 CA-4).
     */
    static VBox rows(List<Map<String, Object>> items, String empty, Function<Map<String, Object>, Node> line) {
        VBox box = new VBox(8);
        if (items.isEmpty()) {
            box.getChildren().add(muted(empty));
            return box;
        }
        items.forEach(item -> box.getChildren().add(line.apply(item)));
        return box;
    }

    /** Repinta quando a lista muda, sem a tela precisar saber quando a resposta chega. */
    final void repaintOn(ObservableList<?>... lists) {
        for (ObservableList<?> list : lists) {
            list.addListener((ListChangeListener<Object>) change -> render());
        }
    }

    /** Cada tela desenha o seu corpo; a base só decide quando. */
    abstract void render();

    static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        return label;
    }

    static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    /** Uma linha com texto à esquerda e botões à direita. */
    static HBox actionRow(Node text, Node... actions) {
        HBox row = new HBox(10, text);
        row.getStyleClass().add("settings-row");
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        row.getChildren().add(spacer);
        row.getChildren().addAll(actions);
        return row;
    }

    static String text(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** Um botão secundário já com id, para o teste achar e o usuário enxergar igual. */
    static Button button(String id, String label, Runnable action) {
        Button button = new Button(label);
        button.setId(id);
        button.getStyleClass().add("button-secondary");
        button.setOnAction(event -> action.run());
        return button;
    }
}
