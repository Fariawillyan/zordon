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
import zordon.desktop.shell.DesktopState;
import zordon.desktop.ui.ShellVoiceActions;

/** A voz pedida pela tela: o modo, o dispositivo, o teste do microfone e a escuta. */
final class DesktopVoiceActions implements ShellVoiceActions {

    private final DesktopContext context;
    private final DesktopState state;

    DesktopVoiceActions(DesktopContext context) {
        this.context = context;
        this.state = context.state();
    }

    @Override
    public void setVoiceMode(String mode) {
        context.request("voice.setMode", Map.of("mode", mode))
                .thenAccept(result -> Platform.runLater(() -> state.voice().apply(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void loadVoiceDevices() {
        context.request("voice.devices", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.voice().devices(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void selectVoiceDevice(String deviceId) {
        context.request("voice.selectDevice", Map.of("deviceId", deviceId))
                .thenAccept(result -> Platform.runLater(() -> state.voice().apply(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void testMicrophone() {
        context.request("voice.testMicrophone", Map.of("seconds", 5))
                .thenAccept(result -> Platform.runLater(() -> state.voice().apply(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void startListening() {
        context.request("voice.startListening", Map.of("reason", "ui"))
                .thenAccept(result -> Platform.runLater(() -> state.voice().apply(result)))
                .exceptionally(this::voiceFailure);
    }

    @Override
    public void stopListening() {
        context.request("voice.stopListening", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.voice().apply(result)))
                .exceptionally(this::voiceFailure);
    }

    void refresh() {
        context.request("voice.status", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.voice().apply(result)))
                .exceptionally(this::voiceFailure);
    }

    private Void voiceFailure(Throwable failure) {
        Platform.runLater(() -> state.voice().failed(DesktopContext.rootMessage(failure)));
        return null;
    }
}
