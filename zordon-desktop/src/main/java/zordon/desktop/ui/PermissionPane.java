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
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import zordon.api.trace.Spec;
import zordon.desktop.shell.SecurityPresentation;

/**
 * O diálogo de permissão (SPEC-015 CA-2): o que o núcleo resumiu, os alvos
 * concretos e a contagem regressiva. "Negar" é o padrão; em RED, "Autorizar" só
 * habilita depois de "Conferi os alvos".
 */
@Spec("SPEC-015")
public final class PermissionPane extends VBox {

    private final CompletableFuture<zordon.desktop.shell.SecurityPresentation.Choice> result = new CompletableFuture<>();
    private final AtomicBoolean decided = new AtomicBoolean();
    private final Timeline countdown;
    private int remaining;

    public PermissionPane(zordon.desktop.shell.SecurityPresentation.Prompt prompt) {
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

        remaining = prompt.seconds();
        Label timer = new Label();
        timer.setId("permission-countdown");
        timer.getStyleClass().add("muted");
        timer.setText(remainingText());

        Button deny = new Button("Negar");
        deny.setId("permission-deny");
        deny.setDefaultButton(true);   // Enter nega (Segurança §2, regra 4)
        deny.setCancelButton(true);    // Esc também
        deny.setOnAction(event -> decide(zordon.desktop.shell.SecurityPresentation.Choice.DENY));
        Button once = new Button(prompt.requiresCheck() ? "Autorizar esta ação" : "Autorizar");
        once.setId("permission-allow");
        once.setOnAction(event -> decide(zordon.desktop.shell.SecurityPresentation.Choice.ONCE));
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttons.getChildren().addAll(timer, spacer, deny);
        if (prompt.offersSession()) {
            Button session = new Button("Autorizar nesta sessão");
            session.setId("permission-session");
            session.setOnAction(event -> decide(zordon.desktop.shell.SecurityPresentation.Choice.SESSION));
            buttons.getChildren().add(session);
        }
        buttons.getChildren().add(once);

        getChildren().addAll(title, risk, origin, summary, targetList);
        if (prompt.requiresCheck()) {
            CheckBox checked = new CheckBox("Conferi os alvos acima");
            checked.setId("permission-checked");
            once.disableProperty().bind(checked.selectedProperty().not());
            getChildren().add(checked);
        }
        getChildren().add(buttons);

        countdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            remaining--;
            timer.setText(remainingText());
            if (remaining <= 0) {
                decide(zordon.desktop.shell.SecurityPresentation.Choice.DENY);
            }
        }));
        countdown.setCycleCount(Math.max(1, prompt.seconds()));
        countdown.play();
    }

    public CompletableFuture<zordon.desktop.shell.SecurityPresentation.Choice> result() {
        return result;
    }

    /** Fechar a janela, perder a conexão ou o tempo acabar: nega. */
    public void decide(zordon.desktop.shell.SecurityPresentation.Choice choice) {
        if (decided.compareAndSet(false, true)) {
            countdown.stop();
            result.complete(choice);
        }
    }

    private String remainingText() {
        return "Nega sozinho em " + Math.max(0, remaining) + " s";
    }

    /** Mostra o diálogo por cima da janela do Zordon, que vem para a frente. */
    public static CompletableFuture<String> show(Window owner,
            zordon.desktop.shell.SecurityPresentation.Prompt prompt, String stylesheet) {
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
        stage.setOnCloseRequest(event -> pane.decide(zordon.desktop.shell.SecurityPresentation.Choice.DENY));
        pane.result().whenComplete((choice, failure) -> javafx.application.Platform.runLater(stage::close));
        stage.show();
        stage.toFront();
        return pane.result().thenApply(SecurityPresentation::answer);
    }
}
