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

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Diagnostics;
import zordon.desktop.shell.SendRule;

/**
 * Composer global: pertence ao centro, fica sempre alcançável, e diz para onde vai
 * antes de enviar ([Layout §4](../../../../../../../docs/specs/ui/desktop-layout.md#composer-global)).
 */
final class ComposerBar extends VBox {

    private static final double MIN_HEIGHT = 112;
    private static final double MAX_HEIGHT = 200;

    private final TextArea field = new TextArea();
    private final Button send = new Button("Enviar", Icons.of("arrow", 16, Color.web("#0A0D12")));
    private final Button cancel = new Button("Cancelar tarefa");
    private final Label target = new Label();
    private final Label model = new Label();
    private final DesktopState state;
    private final ShellActions actions;
    private boolean composing;

    ComposerBar(DesktopState state, ShellActions actions) {
        this.state = state;
        this.actions = actions;
        getStyleClass().add("composer");
        setSpacing(8);
        setPadding(new Insets(12, 16, 12, 16));
        setMinHeight(MIN_HEIGHT);
        setMaxHeight(MAX_HEIGHT);

        target.getStyleClass().add("composer-meta");
        model.getStyleClass().add("composer-meta");
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox top = new HBox(8, Icons.of("spark", 14, Color.web("#3DDCFF")), target, topSpacer, model);
        top.setAlignment(Pos.CENTER_LEFT);

        field.setWrapText(true);
        field.setPrefRowCount(2);
        field.getStyleClass().add("composer-field");
        field.setAccessibleText("Mensagem para o Zordon");
        field.textProperty().bindBidirectional(state.conversation().draftProperty());
        field.addEventHandler(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                event -> composing = !event.getComposed().isEmpty());
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        VBox.setVgrow(field, Priority.ALWAYS);

        Label hint = new Label("Enter para enviar · Shift + Enter para nova linha");
        hint.getStyleClass().add("composer-hint");
        send.getStyleClass().add("button-primary");
        send.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        send.setOnAction(event -> submit());
        cancel.getStyleClass().add("button-secondary");
        cancel.setOnAction(event -> actions.cancelTurn(state.conversation().currentTurnIdProperty().get()));
        cancel.visibleProperty().bind(state.conversation().turnRunningProperty());
        cancel.managedProperty().bind(state.conversation().turnRunningProperty());
        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox bottom = new HBox(12, hint, bottomSpacer, cancel, send);
        bottom.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(top, field, bottom);
        bindState();
    }

    void focusField() {
        field.requestFocus();
    }

    private void bindState() {
        field.disableProperty().bind(Bindings.isNotEmpty(state.conversation().composerBlockedReason()));
        send.disableProperty().bind(Bindings.isNotEmpty(state.conversation().composerBlockedReason())
                .or(Bindings.createBooleanBinding(() -> field.getText().isBlank(), field.textProperty())));
        field.promptTextProperty().bind(Bindings.createStringBinding(
                () -> state.conversation().composerBlockedReason().get().isEmpty()
                        ? "O que vamos fazer agora?"
                        : state.conversation().composerBlockedReason().get(),
                state.conversation().composerBlockedReason()));
        target.textProperty().bind(Bindings.createStringBinding(
                () -> state.composerTarget().label(), state.destinationProperty(), state.conversation().sessionIdProperty()));
        model.textProperty().bind(Bindings.createStringBinding(
                () -> modelLabel(state.diagnosticsProperty().get()), state.diagnosticsProperty()));
    }

    /**
     * Diz se a conversa sai da máquina. Alegar que tudo fica local quando a
     * resposta vem de IA remota seria mentir sobre para onde vão os dados
     * ([Layout §4](../../../../../../../docs/specs/ui/desktop-layout.md#composer-global)).
     */
    private static String modelLabel(Diagnostics diagnostics) {
        if (diagnostics == null) {
            return "";
        }
        var conversation = diagnostics.roles().get("conversation");
        if (conversation == null || !conversation.ready()) {
            return "sem modelo pronto";
        }
        String state = diagnostics.providers().getOrDefault(conversation.provider(), "");
        return (state.contains("local") ? "IA local · " : "IA remota · ") + conversation.provider();
    }

    private void onKey(KeyEvent event) {
        if (event.getCode() != KeyCode.ENTER) {
            return;
        }
        if (SendRule.shouldSend(true, event.isShiftDown(), composing, field.getText())) {
            event.consume();
            submit();
        } else if (!event.isShiftDown() && !composing) {
            // Enter com o campo vazio não quebra linha nem envia.
            event.consume();
        }
    }

    private void submit() {
        String text = field.getText().strip();
        if (text.isEmpty() || field.isDisabled()) {
            return;
        }
        actions.send(text, state.composerTarget());
        field.clear();
    }
}
