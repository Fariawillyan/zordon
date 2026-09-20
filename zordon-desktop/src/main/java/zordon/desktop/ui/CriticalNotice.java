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
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import zordon.api.trace.Spec;

/**
 * Um aviso CRITICAL (SPEC-015 CA-7, Comunicação §3): interrompe, mostra as oito
 * respostas e só sai com "Li e entendi". Fechar a janela não confirma.
 */
@Spec("SPEC-015")
public final class CriticalNotice extends VBox {

    private final Button read = new Button("Li e entendi");

    public CriticalNotice(Map<String, Object> message, Runnable onRead) {
        setId("critical-notice");
        getStyleClass().addAll("permission-dialog", "severity-critical");
        setSpacing(10);
        setPadding(new Insets(20));
        setPrefWidth(560);
        Label title = new Label(String.valueOf(message.getOrDefault("title", "Aviso crítico")));
        title.getStyleClass().add("page-title");
        getChildren().add(title);
        for (String[] row : List.of(
                new String[] {"O que aconteceu", "whatHappened"},
                new String[] {"Por que importa", "whySuspicious"},
                new String[] {"Quem percebeu", "detectedBy"},
                new String[] {"O que foi feito", "actionTaken"},
                new String[] {"Onde", "affectedResource"},
                new String[] {"Estado agora", "currentState"})) {
            Label label = new Label(row[0] + ": " + message.getOrDefault(row[1], "—"));
            label.setWrapText(true);
            getChildren().add(label);
        }
        getChildren().add(new Label(Boolean.TRUE.equals(message.get("reversible"))
                ? "A ação é reversível." : "A ação não é reversível."));
        if (message.get("options") instanceof List<?> options) {
            getChildren().add(new Label("Você pode: " + String.join("; ", options.stream().map(String::valueOf).toList())));
        }
        read.setId("critical-read");
        read.setOnAction(event -> onRead.run());
        getChildren().add(read);
    }

    public static void show(Window owner, Map<String, Object> message, String stylesheet, Consumer<String> onRead) {
        Stage stage = new Stage();
        stage.setTitle("Zordon — aviso crítico");
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        String id = String.valueOf(message.get("messageId"));
        CriticalNotice notice = new CriticalNotice(message, () -> {
            onRead.accept(id);
            stage.close();
        });
        Scene scene = new Scene(notice);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        // Fechar não é confirmar: a mensagem continua pendente e volta na próxima conexão.
        stage.show();
        stage.toFront();
    }
}
