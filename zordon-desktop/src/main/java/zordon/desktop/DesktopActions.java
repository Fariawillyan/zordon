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
package zordon.desktop;

import java.util.Map;
import javafx.application.Platform;
import zordon.desktop.shell.ComposerTarget;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;
import zordon.desktop.ui.ShellActions;
import zordon.desktop.ui.ZordonShell;

/**
 * O que a tela pede ao núcleo. A conversa mora aqui; o resto, num grupo por
 * área, como o estado.
 */
final class DesktopActions implements ShellActions {

    private final DesktopContext context;
    private final DesktopState state;
    private final DesktopVoiceActions voice;
    private final DesktopSecurityActions security;
    private final DesktopMemoryActions memory;
    private final DesktopDataActions data;

    DesktopActions(DesktopContext context) {
        this.context = context;
        this.state = context.state();
        this.voice = new DesktopVoiceActions(context);
        this.security = new DesktopSecurityActions(context);
        this.memory = new DesktopMemoryActions(context);
        this.data = new DesktopDataActions(context);
    }

    @Override
    public void send(String text, ComposerTarget target) {
        if (target.newConversation()) {
            context.request("chat.newSession", Map.of()).thenAccept(result -> Platform.runLater(() -> {
                state.conversation().sessionIdProperty().set(String.valueOf(result.get("sessionId")));
                shell().clearChat();
                state.select(Destination.CHAT);
                sendToSession(text);
            })).exceptionally(context::reportFailure);
        } else {
            sendToSession(text);
        }
    }

    @Override
    public void newConversation() {
        context.request("chat.newSession", Map.of()).thenAccept(result -> Platform.runLater(() -> {
            state.conversation().sessionIdProperty().set(String.valueOf(result.get("sessionId")));
            shell().clearChat();
            state.select(Destination.CHAT);
            shell().focusComposer();
        })).exceptionally(context::reportFailure);
    }

    @Override
    public void cancelTurn(String turnId) {
        if (turnId != null) {
            // Cancelar preserva o que já chegou e espera a confirmação do núcleo.
            context.request("chat.cancel", Map.of("turnId", turnId))
                    .thenAccept(result -> { })
                    .exceptionally(context::reportFailure);
        }
    }

    @Override
    public void refreshDiagnostics() {
        context.request("system.diagnostics", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.diagnostics(result)))
                .exceptionally(failure -> {
                    Platform.runLater(() -> state.diagnosticsFailed(DesktopContext.rootMessage(failure)));
                    return null;
                });
    }

    @Override
    public DesktopVoiceActions voice() {
        return voice;
    }

    @Override
    public DesktopSecurityActions security() {
        return security;
    }

    @Override
    public DesktopMemoryActions memory() {
        return memory;
    }

    @Override
    public DesktopDataActions data() {
        return data;
    }

    private void sendToSession(String text) {
        shell().chatUser(text);
        shell().setLastConversation(text);
        Map<String, Object> params = state.conversation().sessionIdProperty().get() == null
                ? Map.of("text", text)
                : Map.of("text", text, "sessionId", state.conversation().sessionIdProperty().get());
        context.request("chat.send", params)
                .thenAccept(result -> Platform.runLater(
                        () -> state.conversation().sessionIdProperty().set(String.valueOf(result.get("sessionId")))))
                .exceptionally(context::reportFailure);
    }

    private ZordonShell shell() {
        return context.shell();
    }
}
