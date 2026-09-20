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
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
    private final VoiceView voice;
    private final VoiceSettingsView settings;
    private final ChatView chat;
    private final ComposerBar composer;
    private final VBox conversation;
    /** A tela do Zordon, que é a tela principal do trabalho (SPEC-032). */
    private final javafx.scene.Node voiceScreen;
    private final LogsView logs = new LogsView();
    private final VBox logsPage;
    private final DiagnosticsView diagnostics;
    /** Uma tela por destino (SPEC-030): o Painel deixou de prometer o que já existe. */
    private final java.util.Map<Destination, DestinationPage> pages = new java.util.EnumMap<>(Destination.class);
    private final StackPane screens = new StackPane();
    private final Label notice = new Label();

    public ZordonShell(DesktopState state, ShellActions actions) {
        this.state = state;
        getStyleClass().add("root-pane");

        this.rail = new NavigationPane(state, this::navigate);
        this.voice = new VoiceView(state, actions);
        this.settings = new VoiceSettingsView(state, actions);
        // Sem modo técnico (SPEC-031): os metadados da resposta aparecem sempre.
        this.chat = new ChatView(actions::newConversation, new javafx.beans.property.SimpleBooleanProperty(true));
        this.composer = new ComposerBar(state, actions);
        this.diagnostics = new DiagnosticsView(state, actions::refreshDiagnostics);

        // A caixa de texto mora só na Conversa (SPEC-010 CA-1, CA-6).
        this.conversation = new VBox(chat, composer);
        VBox.setVgrow(chat, Priority.ALWAYS);
        conversation.getStyleClass().add("conversation");
        this.logsPage = page("Logs", logs);

        pages.put(Destination.TASKS, new TasksView(state, actions));
        pages.put(Destination.AGENTS, new AgentsView(state, actions));
        pages.put(Destination.MCP, new McpView(state, actions));
        pages.put(Destination.SKILLS, new SkillsView(state, actions));
        pages.put(Destination.AUTOMATIONS, new AutomationsView(state, actions));
        pages.put(Destination.MEMORY, new MemoryView(state, actions));
        pages.put(Destination.KNOWLEDGE, new KnowledgeView(state, actions));
        pages.put(Destination.SYSTEM, new SystemView(state, actions));
        pages.put(Destination.USAGE, new UsageView(state, actions));
        pages.put(Destination.SECURITY, new SecurityView(state, actions));

        // A Voz é só o Zordon: os ajustes têm destino próprio (SPEC-032).
        this.voiceScreen = voice;

        screens.getChildren().addAll(voice, conversation, settings, logsPage, diagnostics);
        screens.getChildren().addAll(pages.values());
        screens.setMinSize(0, 0);
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
        getChildren().add(layout);

        state.destinationProperty().addListener((observable, before, now) -> show(now));
        show(state.destinationProperty().get());
        // Ctrl+Espaço fala com o Zordon de qualquer tela (SPEC-011 CA-7).
        sceneProperty().addListener((observable, before, scene) -> {
            if (scene != null) {
                scene.getAccelerators().put(new javafx.scene.input.KeyCodeCombination(
                        javafx.scene.input.KeyCode.SPACE, javafx.scene.input.KeyCombination.CONTROL_DOWN),
                        () -> VoiceView.toggleListening(state, actions));
            }
        });
    }

    ChatView chat() {
        return chat;
    }

    VoiceView voice() {
        return voice;
    }

    NavigationPane rail() {
        return rail;
    }

    public void close() {
        voice.close();
    }

    public LogsView logs() {
        return logs;
    }

    /** Mostra, na conversa, uma mensagem vinda de fora do fluxo de eventos (histórico, erro local). */
    public void chatUser(String text) {
        chat.addUser(text);
    }

    public void chatAssistant(String text, String footer) {
        chat.addAssistant(text, footer);
    }

    public void chatDelta(String turnId, String delta) {
        chat.appendDelta(turnId, delta);
    }

    public void chatComplete(String turnId, String text, String footer) {
        chat.complete(turnId, text, footer);
    }

    public void chatError(String text) {
        chat.addError(text);
    }

    public void chatNotice(String text) {
        chat.addNotice(text);
    }

    public void clearChat() {
        chat.clear();
    }

    public void setLastConversation(String preview) {
        // O Painel saiu (SPEC-032); a última conversa volta pela própria Conversa.
    }

    public void focusComposer() {
        composer.focusField();
    }

    private void navigate(Destination destination) {
        if (!state.select(destination)) {
            showNotice(destination.unavailableReason());
        }
    }

    private void show(Destination destination) {
        Node chosen = switch (destination) {
            case VOICE -> voiceScreen;
            case CHAT -> conversation;
            case SETTINGS -> settings;
            case LOGS -> logsPage;
            case DIAGNOSTICS -> diagnostics;
            default -> pages.get(destination);
        };
        Node visible = chosen == null ? voiceScreen : chosen;
        screens.getChildren().forEach(node -> node.setVisible(node == visible));
        // A carga sai ao abrir, e só na primeira vez (SPEC-030 CA-3).
        if (visible instanceof DestinationPage opened) {
            opened.opened();
        }
    }

    private void showNotice(String text) {
        notice.setText(text);
        notice.setVisible(true);
        PauseTransition hide = new PauseTransition(Duration.seconds(4));
        hide.setOnFinished(event -> notice.setVisible(false));
        hide.play();
    }

    private static VBox page(String titleText, Node body) {
        Label title = new Label(titleText);
        title.getStyleClass().add("page-title");
        VBox header = new VBox(title);
        header.setPadding(new Insets(20, 24, 16, 24));
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox page = new VBox(header, body);
        page.getStyleClass().add("page");
        return page;
    }
}
