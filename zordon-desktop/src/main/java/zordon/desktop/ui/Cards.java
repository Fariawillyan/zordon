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

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import zordon.api.trace.Spec;

/**
 * O cartão com título que todas as telas usam.
 *
 * <p>Morava no {@code HomeView}; quando o Painel saiu (SPEC-032), o utilitário
 * ficou — ele nunca foi do Painel, só estava hospedado lá.
 */
@Spec("SPEC-032")
final class Cards {

    private Cards() {
    }

    static VBox section(String title, Node body) {
        Label label = new Label(title);
        label.getStyleClass().add("card-title");
        VBox card = new VBox(12, label, body);
        card.getStyleClass().add("card");
        return card;
    }
}
