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

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;

/**
 * A coluna de navegação (SPEC-031): logo, todos os destinos agrupados, e no pé o
 * botão de status do núcleo.
 *
 * <p>Substituiu o trilho de quatro ícones da SPEC-010. A tela de Ajustes com abas
 * deixou de existir: o que era aba virou destino, e não há mais modo técnico
 * escondendo parte da navegação.
 */
@Spec("SPEC-031")
final class NavigationPane extends VBox {

    /**
     * Largura da coluna.
     *
     * <p>144 e não mais: o console de voz tem mínimo de ~575 px e a janela mínima
     * é de 720 (SPEC-010 CA-2) — sobram 145. Alargar a coluna aqui faz o console
     * transbordar na menor janela suportada; o jeito de alargar é encolher a coluna
     * para só ícones em janela estreita, que fica para depois.
     */
    static final double WIDTH = 144;

    private final NavigationList destinations;
    private final CoreStatusButton statusButton;

    NavigationPane(DesktopState state, Consumer<Destination> onSelect) {
        getStyleClass().add("navigation");
        setAlignment(Pos.TOP_LEFT);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        setPadding(new Insets(18, 4, 12, 4));

        destinations = new NavigationList(onSelect);
        VBox.setVgrow(destinations, Priority.ALWAYS);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        statusButton = new CoreStatusButton(state, onSelect);

        Label version = new Label("v0.1.0");
        version.getStyleClass().add("nav-footer-version");
        Label online = new Label();
        online.getStyleClass().add("nav-footer-version");
        online.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> switch (state.connection().stateProperty().get()) {
                    case ONLINE -> "Online";
                    case CONNECTING -> "Conectando";
                    case OFFLINE -> "Offline";
                }, state.connection().stateProperty()));
        HBox footer = new HBox(6, version, statusButton, online);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(8, 0, 0, 8));

        getChildren().addAll(new NavigationBrand(), destinations, spacer, footer);

        state.destinationProperty().addListener((observable, before, now) -> destinations.highlight(now));
        destinations.highlight(state.destinationProperty().get());
    }

    /** Texto do indicador do núcleo, também dito pelo leitor de tela (SPEC-010 CA-4). */
    String coreText() {
        return statusButton.coreText();
    }

    /**
     * O traço que muda de cor com o estado do núcleo.
     *
     * <p>Desde que ele virou o conteúdo do botão de status, {@code lookup(".rail-core")}
     * só o encontra depois de o skin do botão existir — isto é, com a janela montada.
     * O acessório evita que um teste de regra precise de uma tela inteira.
     */
    Node coreIndicator() {
        return statusButton.indicator();
    }

    /** O item de um destino. Como a coluna rola, {@code lookup} depende do skin. */
    Button item(Destination destination) {
        return destinations.item(destination);
    }
}
