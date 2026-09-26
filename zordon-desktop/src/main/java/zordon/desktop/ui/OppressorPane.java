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

import java.util.concurrent.CompletableFuture;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import zordon.api.trace.Spec;

/**
 * O pedido da senha mestre do OPPRESSOR MODE (SPEC-036 CA-9).
 *
 * <p>A voz abre este diálogo e para por aí: quem autoriza é quem digita, na
 * tela. É a mesma regra do [ADR-0030] para tudo que a voz aciona — qualquer
 * pessoa na sala, ou um vídeo tocando, consegue dizer a frase; ninguém
 * consegue digitar a senha sem estar na máquina.
 */
@Spec("SPEC-036")
public final class OppressorPane extends VBox {

    private final PasswordField key = new PasswordField();
    private final CompletableFuture<char[]> result = new CompletableFuture<>();

    private OppressorPane() {
        getStyleClass().addAll("root-pane", "oppressor");
        setPadding(new Insets(20));
        setSpacing(12);
        setPrefWidth(460);

        Label title = new Label("OPPRESSOR MODE");
        title.getStyleClass().add("card-title");
        Label body = new Label("Digite a senha mestre. Enquanto o modo estiver ativo, toda ordem executa "
                + "direto: sem confirmação e sem teto por origem. Ele fica assim até você sair ou o "
                + "Zordon encerrar.");
        body.setWrapText(true);

        key.setId("oppressor-prompt-key");
        key.setPromptText("senha mestre");
        key.setOnAction(event -> submit());

        Button enter = new Button("Entrar");
        enter.setId("oppressor-prompt-enter");
        enter.getStyleClass().setAll("button", "button-danger");
        enter.setOnAction(event -> submit());
        Button cancel = new Button("Cancelar");
        cancel.setId("oppressor-prompt-cancel");
        cancel.getStyleClass().setAll("button", "button-secondary");
        cancel.setOnAction(event -> result.complete(new char[0]));
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, enter);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(title, body, key, buttons);
    }

    private void submit() {
        result.complete(key.getText().toCharArray());
        key.clear();
    }

    CompletableFuture<char[]> result() {
        return result;
    }

    /**
     * Mostra o pedido por cima da janela do Zordon.
     *
     * @return a senha digitada, ou um vetor vazio se o usuário desistiu
     */
    public static CompletableFuture<char[]> show(Window owner, String stylesheet) {
        OppressorPane pane = new OppressorPane();
        Stage stage = new Stage();
        stage.setTitle("Zordon — OPPRESSOR MODE");
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        Scene scene = new Scene(pane);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        // Fechar pela cruz é desistir, não entrar: o vetor vazio nunca confere.
        stage.setOnCloseRequest(event -> pane.result.complete(new char[0]));
        pane.result.whenComplete((password, failure) -> javafx.application.Platform.runLater(stage::close));
        stage.show();
        stage.toFront();
        pane.key.requestFocus();
        return pane.result;
    }
}
