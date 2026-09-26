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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** O cabeçalho da conversa: o título da sessão e o botão de começar outra. */
final class ChatHeader extends HBox {

    ChatHeader(Runnable onNewConversation) {
        super(12);
        Label title = new Label("Conversa atual");
        title.getStyleClass().add("page-title");
        Button create = new Button("Nova conversa", Icons.of("plus", 16, Color.web("#9AA7BC")));
        create.getStyleClass().add("button-tertiary");
        create.setOnAction(event -> onNewConversation.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(title, spacer, create);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(20, 24, 16, 24));
        getStyleClass().add("page-header");
    }
}
