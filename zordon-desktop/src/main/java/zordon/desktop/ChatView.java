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

import java.util.HashMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import java.util.function.Consumer;

/**
 * A conversa e o campo de comando.
 *
 * <p>Sem regra de negócio: recebe, delega e apresenta. O que decide qualquer coisa
 * está no núcleo ([UI §1](../../../../../docs/specs/ui/design.md#1-papel)).
 */
final class ChatView extends BorderPane {

    private final VBox thread = new VBox(12);
    private final ScrollPane scroll = new ScrollPane(thread);
    private final TextArea composer = new TextArea();
    private final Button send = new Button("Enviar");
    private final Label thinking = new Label("pensando…");
    private final Map<String, TextFlow> streaming = new HashMap<>();
    private final Consumer<String> onSend;

    ChatView(Consumer<String> onSend) {
        this.onSend = onSend;
        getStyleClass().add("chat-view");

        thread.setPadding(new Insets(24));
        thread.getStyleClass().add("chat-thread");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("chat-scroll");

        thinking.getStyleClass().add("thinking-indicator");
        thinking.setVisible(false);
        thinking.setManaged(false);

        setCenter(scroll);
        setBottom(composerArea());
    }

    private Region composerArea() {
        composer.setPromptText("Pergunte ao Zordon");
        composer.setWrapText(true);
        composer.setPrefRowCount(2);
        composer.getStyleClass().add("composer-field");
        composer.setOnKeyPressed(event -> {
            // Enter envia; Shift+Enter quebra linha. Enter nunca confirma um diálogo
            // de permissão — lá a regra é o contrário (UI §6).
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                submit();
            }
        });

        send.getStyleClass().add("button-primary");
        send.setOnAction(event -> submit());
        send.setDefaultButton(false);

        HBox row = new HBox(12, composer, send);
        HBox.setHgrow(composer, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_RIGHT);

        VBox bottom = new VBox(8, thinking, row);
        bottom.setPadding(new Insets(16, 24, 16, 24));
        bottom.getStyleClass().add("composer");
        return bottom;
    }

    private void submit() {
        String text = composer.getText().strip();
        if (text.isEmpty() || composer.isDisable()) {
            return;
        }
        composer.clear();
        onSend.accept(text);
    }

    void addUserMessage(String text) {
        thread.getChildren().add(bubble("você", text, "message-user"));
        scrollToEnd();
    }

    /** Primeiro fragmento cria a bolha; os seguintes só acrescentam texto. */
    void appendAssistantDelta(String turnId, String delta) {
        TextFlow flow = streaming.computeIfAbsent(turnId, id -> {
            VBox bubble = bubble("Zordon", "", "message-assistant");
            thread.getChildren().add(bubble);
            return (TextFlow) bubble.getChildren().getLast();
        });
        flow.getChildren().add(text(delta));
        scrollToEnd();
    }

    /**
     * O texto completo substitui os fragmentos.
     *
     * <p>Não é redundância: o tópico de chat pode descartar evento sob pressão, e é
     * este evento que reconcilia o que a tela mostra com o que o núcleo registrou.
     */
    void completeAssistant(String turnId, String fullText, String footer) {
        TextFlow flow = streaming.remove(turnId);
        if (flow == null) {
            if (!fullText.isBlank()) {
                thread.getChildren().add(bubble("Zordon", fullText, "message-assistant"));
            }
        } else {
            flow.getChildren().setAll(text(fullText));
        }
        if (!footer.isBlank()) {
            Label meta = new Label(footer);
            meta.getStyleClass().add("message-meta");
            thread.getChildren().add(meta);
        }
        setThinking(false);
        scrollToEnd();
    }

    void showError(String message, boolean retryable) {
        VBox bubble = bubble("erro", message + (retryable ? "  ·  dá para tentar de novo" : ""), "message-error");
        thread.getChildren().add(bubble);
        setThinking(false);
        scrollToEnd();
    }

    /** Aviso do sistema no meio da conversa — não é fala de ninguém. */
    void showNotice(String text) {
        Label notice = new Label(text);
        notice.setWrapText(true);
        notice.getStyleClass().add("message-notice");
        thread.getChildren().add(notice);
        scrollToEnd();
    }

    void setThinking(boolean active) {
        thinking.setVisible(active);
        thinking.setManaged(active);
    }

    void setComposerEnabled(boolean enabled, String reason) {
        composer.setDisable(!enabled);
        send.setDisable(!enabled);
        composer.setPromptText(enabled ? "Pergunte ao Zordon" : reason);
    }

    private VBox bubble(String author, String content, String styleClass) {
        Label who = new Label(author);
        who.getStyleClass().add("message-author");

        TextFlow flow = new TextFlow();
        if (!content.isEmpty()) {
            flow.getChildren().add(text(content));
        }
        flow.getStyleClass().add("message-text");

        VBox box = new VBox(4, who, flow);
        box.getStyleClass().addAll("message", styleClass);
        return box;
    }

    private Text text(String content) {
        Text node = new Text(content);
        node.getStyleClass().add("message-run");
        return node;
    }

    /** Rolagem só acompanha quando o usuário já estava no fim — streaming não rouba a leitura. */
    private void scrollToEnd() {
        if (scroll.getVvalue() > 0.95 || scroll.getVvalue() == 0) {
            javafx.application.Platform.runLater(() -> scroll.setVvalue(1.0));
        }
    }
}
