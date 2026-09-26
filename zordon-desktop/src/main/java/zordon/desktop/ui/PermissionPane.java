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
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import zordon.api.trace.Spec;
import zordon.desktop.shell.SecurityPresentation;
import zordon.desktop.shell.SecurityPresentation.Choice;
import zordon.desktop.shell.SecurityPresentation.Prompt;

/**
 * O diálogo de permissão (SPEC-015 CA-2): o que o núcleo resumiu, os alvos
 * concretos e a contagem regressiva. "Negar" é o padrão; em RED, "Autorizar" só
 * habilita depois de "Conferi os alvos".
 */
@Spec("SPEC-015")
public final class PermissionPane extends VBox {

    private final CompletableFuture<Choice> result = new CompletableFuture<>();
    private final AtomicBoolean decided = new AtomicBoolean();
    private final PermissionActions actions;

    public PermissionPane(Prompt prompt) {
        getStyleClass().add("permission-dialog");
        setId("permission-dialog");
        setSpacing(12);
        setPadding(new Insets(20));
        setPrefWidth(560);

        Label title = new Label(prompt.title());
        title.getStyleClass().add("page-title");
        Label risk = new Label(prompt.risk());
        risk.getStyleClass().add(prompt.requiresCheck() ? "warning" : "muted");
        Label origin = new Label(prompt.origin());
        origin.getStyleClass().add("muted");
        Label summary = new Label(prompt.summary());
        summary.setId("permission-summary");
        summary.setWrapText(true);

        VBox targets = new VBox(4);
        prompt.targets().forEach(target -> {
            Label line = new Label("•  " + target);
            line.getStyleClass().add("mono");
            targets.getChildren().add(line);
        });
        if (prompt.hiddenTargets() > 0) {
            targets.getChildren().add(new Label("… e mais " + prompt.hiddenTargets() + " alvos"));
        }
        ScrollPane targetList = new ScrollPane(targets);
        targetList.setId("permission-targets");
        targetList.setFitToWidth(true);
        targetList.setPrefViewportHeight(Math.min(160, 22 * Math.max(1, targets.getChildren().size())));

        actions = new PermissionActions(prompt, this::decide);

        getChildren().addAll(title, risk, origin, summary, targetList);
        if (prompt.requiresCheck()) {
            CheckBox checked = new CheckBox("Conferi os alvos acima");
            checked.setId("permission-checked");
            actions.requireCheck(checked);
            getChildren().add(checked);
        }
        getChildren().add(actions);
        actions.start();
    }

    public CompletableFuture<Choice> result() {
        return result;
    }

    /** Fechar a janela, perder a conexão ou o tempo acabar: nega. */
    public void decide(Choice choice) {
        if (decided.compareAndSet(false, true)) {
            actions.stop();
            result.complete(choice);
        }
    }

    /** Mostra o diálogo por cima da janela do Zordon, que vem para a frente. */
    public static CompletableFuture<String> show(Window owner, Prompt prompt, String stylesheet) {
        PermissionPane pane = new PermissionPane(prompt);
        Stage stage = new Stage();
        stage.setTitle("Zordon — autorização");
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        Scene scene = new Scene(pane);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> pane.decide(Choice.DENY));
        pane.result().whenComplete((choice, failure) -> javafx.application.Platform.runLater(stage::close));
        stage.show();
        stage.toFront();
        return pane.result().thenApply(SecurityPresentation::answer);
    }
}
