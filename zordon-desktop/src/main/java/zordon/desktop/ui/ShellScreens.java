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

import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;

/** As telas do shell, empilhadas: só a do destino atual fica visível. */
final class ShellScreens extends StackPane {

    private final VoiceView voice;
    private final VoiceSettingsView settings;
    private final ChatView chat;
    private final ComposerBar composer;
    private final VBox conversation;
    /** A tela do Zordon, que é a tela principal do trabalho (SPEC-032). */
    private final Node voiceScreen;
    private final LogsView logs = new LogsView();
    private final VBox logsPage;
    private final DiagnosticsView diagnostics;
    private final Map<Destination, DestinationPage> pages;

    ShellScreens(DesktopState state, ShellActions actions) {
        this.voice = new VoiceView(state, actions);
        this.settings = new VoiceSettingsView(state, actions);
        // Sem modo técnico (SPEC-031): os metadados da resposta aparecem sempre.
        this.chat = new ChatView(actions::newConversation);
        this.composer = new ComposerBar(state, actions);
        this.diagnostics = new DiagnosticsView(state, actions::refreshDiagnostics);

        // A caixa de texto mora só na Conversa (SPEC-010 CA-1, CA-6).
        this.conversation = new VBox(chat, composer);
        VBox.setVgrow(chat, Priority.ALWAYS);
        conversation.getStyleClass().add("conversation");
        this.logsPage = page("Logs", logs);
        this.pages = DestinationPages.create(state, actions);

        // A Voz é só o Zordon: os ajustes têm destino próprio (SPEC-032).
        this.voiceScreen = voice;

        getChildren().addAll(voice, conversation, settings, logsPage, diagnostics);
        getChildren().addAll(pages.values());
        setMinSize(0, 0);
    }

    ChatView chat() {
        return chat;
    }

    VoiceView voice() {
        return voice;
    }

    LogsView logs() {
        return logs;
    }

    void focusComposer() {
        composer.focusField();
    }

    void show(Destination destination) {
        Node chosen = switch (destination) {
            case VOICE -> voiceScreen;
            case CHAT -> conversation;
            case SETTINGS -> settings;
            case LOGS -> logsPage;
            case DIAGNOSTICS -> diagnostics;
            default -> pages.get(destination);
        };
        Node visible = chosen == null ? voiceScreen : chosen;
        getChildren().forEach(node -> node.setVisible(node == visible));
        // A carga sai ao abrir, e só na primeira vez (SPEC-030 CA-3).
        if (visible instanceof DestinationPage opened) {
            opened.opened();
        }
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
