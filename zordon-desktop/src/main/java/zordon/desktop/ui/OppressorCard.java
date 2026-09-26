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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import zordon.desktop.shell.DesktopState;

/** O cartão do OPPRESSOR MODE na Segurança (SPEC-036). */
final class OppressorCard {

    /** Mínimo da senha mestre (SPEC-036 §10); o núcleo reforça, isto é cortesia. */
    private static final int OPPRESSOR_MIN = 12;

    private OppressorCard() {
    }

    /**
     * OPPRESSOR MODE (SPEC-036): a senha mestre, o liga/desliga e a troca.
     *
     * <p>É a única porta do modo pela janela, e o texto diz sem rodeio o que
     * ele faz: ligar isto tira o motor de permissão do caminho de toda ação. O
     * corpo é reconstruído a cada mudança de estado, como os achados e os
     * disjuntores acima.
     */
    static Node oppressor(DesktopState state, ShellActions actions) {
        Label status = new Label();
        status.setWrapText(true);
        status.setId("oppressor-status");
        // A falha mora aqui, ao lado do botão que a causou: no chat ela sumia.
        Label failure = new Label();
        failure.setId("oppressor-failure");
        failure.getStyleClass().add("warning-text");
        failure.setWrapText(true);
        failure.textProperty().bind(state.security().oppressorErrorProperty());
        failure.visibleProperty().bind(failure.textProperty().isNotEmpty());
        failure.managedProperty().bind(failure.visibleProperty());
        VBox controls = new VBox(8);
        controls.setId("oppressor-controls");

        Runnable render = () -> {
            controls.getChildren().clear();
            if (state.security().oppressorProperty().get()) {
                status.setText("Ativo. Toda ordem executa direto: sem confirmação e sem teto por origem. "
                        + "O lockdown ainda vale, e sair volta tudo ao normal.");
                Button exit = new Button("Sair do OPPRESSOR MODE");
                exit.setId("oppressor-exit");
                exit.getStyleClass().setAll("button", "button-primary");
                exit.setOnAction(event -> actions.security().exitOppressor());
                controls.getChildren().add(exit);
                return;
            }
            boolean configured = state.security().oppressorConfiguredProperty().get();
            status.setText(configured
                    ? "Desligado. Com a senha mestre, sua ordem executa direto — sem as perguntas de permissão."
                    : "Desligado e sem senha mestre. Defina uma senha para poder ativar.");
            if (configured) {
                controls.getChildren().add(oppressorEnter(actions));
            }
            controls.getChildren().add(oppressorPassword(actions, configured));
        };
        state.security().oppressorProperty().addListener((observable, before, now) -> render.run());
        state.security().oppressorConfiguredProperty().addListener((observable, before, now) -> render.run());
        render.run();
        return Cards.section("OPPRESSOR MODE", new VBox(8, status, failure, controls));
    }

    /** O campo da senha e o botão de entrar. */
    private static Node oppressorEnter(ShellActions actions) {
        PasswordField key = new PasswordField();
        key.setId("oppressor-key");
        key.setPromptText("senha mestre");
        HBox.setHgrow(key, Priority.ALWAYS);
        Button enter = new Button("Entrar");
        enter.setId("oppressor-enter");
        enter.getStyleClass().setAll("button", "button-danger");
        Runnable go = () -> {
            actions.security().enterOppressor(key.getText().toCharArray());
            key.clear();
        };
        enter.setOnAction(event -> go.run());
        key.setOnAction(event -> go.run());
        return new HBox(8, key, enter);
    }

    /** Cadastrar ou trocar a senha, com a validação que o núcleo também faz. */
    private static Node oppressorPassword(ShellActions actions, boolean configured) {
        PasswordField current = new PasswordField();
        current.setId("oppressor-current");
        current.setPromptText("senha atual");
        PasswordField next = new PasswordField();
        next.setId("oppressor-next");
        next.setPromptText("nova senha (mín. " + OPPRESSOR_MIN + ")");
        PasswordField confirm = new PasswordField();
        confirm.setId("oppressor-confirm");
        confirm.setPromptText("repita a nova senha");
        Label problem = new Label();
        problem.setId("oppressor-problem");
        problem.getStyleClass().add("warning-text");
        problem.setWrapText(true);
        problem.setVisible(false);
        problem.managedProperty().bind(problem.visibleProperty());
        Button save = new Button(configured ? "Trocar senha" : "Definir senha");
        save.setId("oppressor-save");
        save.setOnAction(event -> {
            String candidate = next.getText();
            if (candidate.length() < OPPRESSOR_MIN) {
                warn(problem, "A senha mestre tem no mínimo " + OPPRESSOR_MIN + " caracteres.");
                return;
            }
            if (!candidate.equals(confirm.getText())) {
                warn(problem, "As senhas não conferem.");
                return;
            }
            problem.setVisible(false);
            actions.security().setOppressorPassword(current.getText().toCharArray(), candidate.toCharArray());
            current.clear();
            next.clear();
            confirm.clear();
        });
        VBox box = new VBox(8, SettingsRows.muted(configured ? "Trocar a senha mestre:" : "Definir a senha mestre:"));
        if (configured) {
            box.getChildren().add(current);
        }
        box.getChildren().addAll(next, confirm, problem, save);
        return box;
    }

    private static void warn(Label label, String text) {
        label.setText(text);
        label.setVisible(true);
    }
}
