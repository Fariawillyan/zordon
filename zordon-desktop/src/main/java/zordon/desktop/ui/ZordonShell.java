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

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;

/**
 * O shell compacto da SPEC-010: o trilho de ícones e a tela do destino. Sem
 * cabeçalho, sem barra de estado e sem caixa de texto fora da Conversa; a Voz é
 * a tela inicial e ocupa a janela.
 *
 * <p>Monta e troca telas. Não decide nada: disponibilidade e repouso vêm de
 * classes puras em {@code zordon.desktop.shell}.
 */
@Spec("SPEC-010")
public final class ZordonShell extends StackPane {

    private final DesktopState state;
    private final NavigationPane rail;
    private final ShellScreens screens;
    private final Label notice = new Label();
    /**
     * A faixa do OPPRESSOR MODE (SPEC-036 CA-8).
     *
     * <p>Fica na pilha da raiz, acima de qualquer tela, porque o aviso não pode
     * depender de o usuário estar na aba certa: enquanto o modo dura, o motor
     * de permissão está fora do caminho em toda ação, não só nesta tela.
     */
    private final Label oppressor = new Label("O P P R E S S O R   M O D E");

    public ZordonShell(DesktopState state, ShellActions actions) {
        this.state = state;
        getStyleClass().add("root-pane");

        this.rail = new NavigationPane(state, this::navigate);
        this.screens = new ShellScreens(state, actions);
        notice.getStyleClass().add("notice");
        notice.setVisible(false);
        notice.setWrapText(true);
        notice.setMaxWidth(520);
        notice.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(notice, Pos.TOP_CENTER);
        StackPane.setMargin(notice, new Insets(16));
        NotificationBar alerts = new NotificationBar(state, actions,
                () -> state.select(Destination.SECURITY));
        StackPane.setAlignment(alerts, Pos.TOP_RIGHT);
        StackPane.setMargin(alerts, new Insets(12, 16, 0, 16));
        StackPane work = new StackPane(screens, notice, alerts);
        work.setMinSize(0, 0);
        HBox.setHgrow(work, Priority.ALWAYS);

        HBox layout = new HBox(rail, work);
        oppressor.getStyleClass().add("oppressor-banner");
        oppressor.setMouseTransparent(true);
        oppressor.setMaxWidth(Double.MAX_VALUE);
        oppressor.setAlignment(Pos.CENTER);
        oppressor.visibleProperty().bind(state.security().oppressorProperty());
        oppressor.managedProperty().bind(oppressor.visibleProperty());
        StackPane.setAlignment(oppressor, Pos.TOP_CENTER);
        getChildren().addAll(layout, oppressor);
        // A janela inteira muda de cor: o CSS pendura tudo nesta classe.
        state.security().oppressorProperty().addListener((observable, before, now) -> theme(now));
        theme(state.security().oppressorProperty().get());

        state.destinationProperty().addListener((observable, before, now) -> screens.show(now));
        screens.show(state.destinationProperty().get());
    }

    ChatView chat() {
        return screens.chat();
    }

    VoiceView voice() {
        return screens.voice();
    }

    NavigationPane rail() {
        return rail;
    }

    public void close() {
        screens.voice().close();
    }

    public LogsView logs() {
        return screens.logs();
    }

    /** Mostra, na conversa, uma mensagem vinda de fora do fluxo de eventos (histórico, erro local). */
    public void chatUser(String text) {
        screens.chat().addUser(text);
    }

    public void chatAssistant(String text, String footer) {
        screens.chat().addAssistant(text, footer);
    }

    public void chatDelta(String turnId, String delta) {
        screens.chat().appendDelta(turnId, delta);
    }

    public void chatComplete(String turnId, String text, String footer) {
        screens.chat().complete(turnId, text, footer);
    }

    public void chatError(String text) {
        screens.chat().addError(text);
    }

    public void chatNotice(String text) {
        screens.chat().addNotice(text);
    }

    public void clearChat() {
        screens.chat().clear();
    }

    public void setLastConversation(String preview) {
        // O Painel saiu (SPEC-032); a última conversa volta pela própria Conversa.
    }

    public void focusComposer() {
        screens.focusComposer();
    }

    /** Liga ou desliga o tema do OPPRESSOR MODE na raiz (SPEC-036 CA-8). */
    private void theme(boolean active) {
        getStyleClass().remove("oppressor");
        if (active) {
            getStyleClass().add("oppressor");
        }
    }

    private void navigate(Destination destination) {
        if (!state.select(destination)) {
            showNotice(destination.unavailableReason());
        }
    }

    private void showNotice(String text) {
        notice.setText(text);
        notice.setVisible(true);
        PauseTransition hide = new PauseTransition(Duration.seconds(4));
        hide.setOnFinished(event -> notice.setVisible(false));
        hide.play();
    }
}
