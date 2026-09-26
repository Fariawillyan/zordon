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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import zordon.api.trace.Spec;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Diagnostics;
import zordon.desktop.shell.VoiceStatus;
import zordon.zwp.CoreConnection;

/**
 * A faixa de estado sob o console (SPEC-032): microfone, voz, modelo e memória.
 *
 * <p>Quatro respostas que o usuário procura antes de falar, sem sair da tela. O
 * ponto colorido diz o mesmo que a palavra — cor nunca é o único sinal
 * (Design system §3).
 */
@Spec("SPEC-032")
final class VoiceStatusStrip extends HBox {

    private static final String OK = "#2BD9A6";
    private static final String WARN = "#F0B341";
    private static final String OFF = "#60758E";

    private final DesktopState state;
    private final VBox microphone = card("Microfone");
    private final VBox speech = card("Voz (TTS)");
    private final VBox model = card("Modelo");
    private final VBox memory = card("Memória");

    VoiceStatusStrip(DesktopState state) {
        super(12);
        this.state = state;
        setId("voice-status-strip");
        getStyleClass().add("voice-status-strip");
        setPadding(new Insets(0, 24, 18, 24));
        setAlignment(Pos.CENTER);
        getChildren().addAll(microphone, speech, model, memory);
        getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));

        state.voice().statusProperty().addListener((observable, before, now) -> render());
        state.connection().stateProperty().addListener((observable, before, now) -> render());
        state.diagnosticsProperty().addListener((observable, before, now) -> render());
        render();
    }

    private void render() {
        VoiceStatus voice = state.voice().statusProperty().get();
        boolean online = state.connection().stateProperty().get() == CoreConnection.State.ONLINE;
        renderMicrophone(voice);
        renderSpeech(voice);
        renderModel(online);
        set(memory, online ? "Ativa" : "Sem núcleo", online ? OK : OFF);
    }

    private void renderMicrophone(VoiceStatus voice) {
        if (voice == null || !voice.hostConnected()) {
            set(microphone, "Não conectado", OFF);
        } else if ("on".equals(voice.capture())) {
            set(microphone, voice.device() == null || voice.device().isBlank() ? "Ligado" : voice.device(), OK);
        } else {
            set(microphone, "Desligado", WARN);
        }
    }

    private void renderSpeech(VoiceStatus voice) {
        if (voice == null) {
            set(speech, "Sem resposta", OFF);
        } else if ("ready".equals(voice.engine())) {
            set(speech, "Pronto", OK);
        } else {
            set(speech, voice.engineReason() == null || voice.engineReason().isBlank()
                    ? "Não instalado" : voice.engineReason(), WARN);
        }
    }

    private void renderModel(boolean online) {
        Diagnostics diagnostics = state.diagnosticsProperty().get();
        var conversation = diagnostics == null ? null : diagnostics.roles().get("conversation");
        if (conversation == null) {
            set(model, online ? "Sem provider" : "—", online ? WARN : OFF);
        } else if (conversation.ready()) {
            set(model, conversation.provider() + " · " + conversation.model(), OK);
        } else {
            set(model, conversation.provider() + " indisponível", WARN);
        }
    }

    private static VBox card(String title) {
        Label name = new Label(title);
        name.getStyleClass().add("status-card-title");
        Label value = new Label("—");
        value.getStyleClass().add("status-card-value");
        value.setWrapText(false);
        Circle dot = new Circle(4);
        dot.getStyleClass().add("status-card-dot");
        HBox line = new HBox(7, dot, value);
        line.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, name, line);
        box.getStyleClass().add("status-card");
        box.setPadding(new Insets(12, 14, 12, 14));
        box.setMinWidth(0);
        return box;
    }

    /** O texto e a cor do ponto andam juntos: um sem o outro seria só decoração. */
    private static void set(VBox card, String text, String color) {
        HBox line = (HBox) card.getChildren().get(1);
        Circle dot = (Circle) line.getChildren().getFirst();
        Label value = (Label) line.getChildren().get(1);
        dot.setFill(javafx.scene.paint.Color.web(color));
        value.setText(text);
        card.setAccessibleText(((Label) card.getChildren().getFirst()).getText() + ": " + text);
    }

    /** O valor mostrado num cartão, para o teste conferir sem olhar pixel. */
    String value(int index) {
        VBox card = (VBox) getChildren().get(index);
        return ((Label) ((HBox) card.getChildren().get(1)).getChildren().get(1)).getText();
    }
}
