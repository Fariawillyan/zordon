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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import zordon.desktop.shell.ScrollFollow;

/**
 * A conversa: cabeçalho da sessão, mensagens virtualizadas e o aviso de novas
 * mensagens ([Layout §4](../../../../../../../docs/specs/ui/desktop-layout.md#4-início-e-chat)).
 *
 * <p>Virtualizada porque uma conversa longa com um nó por mensagem trava a janela;
 * a lista só cria células para o que está visível ([UI §4](../../../../../../../docs/specs/ui/design.md#4-regras-de-threading)).
 */
final class ChatView extends VBox {

    /** Uma mensagem na tela. O texto é observável para o streaming crescer no lugar. */
    static final class Item {
        final String role;
        final String author;
        final String time;
        final StringProperty text = new SimpleStringProperty("");
        final StringProperty meta = new SimpleStringProperty("");

        Item(String role, String author, String text) {
            this.role = role;
            this.author = author;
            this.time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            this.text.set(text);
        }
    }

    private final ObservableList<Item> items = FXCollections.observableArrayList();
    private final ListView<Item> list = new ListView<>(items);
    private final java.util.HashMap<String, Item> streaming = new java.util.HashMap<>();
    private final ScrollFollow follow = new ScrollFollow();
    private final Button newMessages = new Button("Novas mensagens ↓");

    /** Metadados técnicos (provider, tempo, tokens) só no modo técnico (SPEC-012 CA-8). */
    private final javafx.beans.value.ObservableBooleanValue technical;

    ChatView(Runnable onNewConversation) {
        this(onNewConversation, new javafx.beans.property.SimpleBooleanProperty(true));
    }

    ChatView(Runnable onNewConversation, javafx.beans.value.ObservableBooleanValue technical) {
        this.technical = technical;
        getStyleClass().add("chat-view");

        Label title = new Label("Conversa atual");
        title.getStyleClass().add("page-title");
        Button create = new Button("Nova conversa", Icons.of("plus", 16, Color.web("#9AA7BC")));
        create.getStyleClass().add("button-tertiary");
        create.setOnAction(event -> onNewConversation.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, create);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(20, 24, 16, 24));
        header.getStyleClass().add("page-header");

        list.getStyleClass().add("chat-list");
        list.setCellFactory(view -> new MessageCell());
        list.setFocusTraversable(false);
        list.setPlaceholder(placeholder());

        newMessages.getStyleClass().add("new-messages");
        newMessages.setVisible(false);
        newMessages.setOnAction(event -> scrollToEnd());
        StackPane stack = new StackPane(list, newMessages);
        StackPane.setAlignment(newMessages, Pos.BOTTOM_CENTER);
        StackPane.setMargin(newMessages, new Insets(0, 0, 12, 0));
        VBox.setVgrow(stack, Priority.ALWAYS);

        getChildren().addAll(header, stack);
    }

    void clear() {
        items.clear();
        streaming.clear();
        newMessages.setVisible(false);
    }

    void addUser(String text) {
        append(new Item("user", "Você", text));
    }

    void addAssistant(String text, String footer) {
        Item item = new Item("assistant", "Zordon", text);
        item.meta.set(footer);
        append(item);
    }

    void addError(String text) {
        append(new Item("error", "Erro", text));
    }

    void addNotice(String text) {
        append(new Item("notice", "", text));
    }

    /** Primeiro fragmento cria a mensagem; os seguintes crescem nela, no lugar. */
    void appendDelta(String turnId, String delta) {
        Item item = streaming.get(turnId);
        if (item == null) {
            item = new Item("assistant", "Zordon", "");
            streaming.put(turnId, item);
            append(item);
        }
        item.text.set(item.text.get() + delta);
        onNewContent();
    }

    /**
     * O texto completo substitui os fragmentos: o tópico de chat pode descartar
     * evento sob pressão, e é este que reconcilia a tela com o que o núcleo guardou.
     */
    void complete(String turnId, String text, String footer) {
        Item item = streaming.remove(turnId);
        if (item == null) {
            if (!text.isBlank()) {
                addAssistant(text, footer);
            }
            return;
        }
        item.text.set(text);
        item.meta.set(footer);
    }

    int renderedCells() {
        return (int) list.lookupAll(".list-cell").stream().filter(node -> ((ListCell<?>) node).getItem() != null).count();
    }

    int size() {
        return items.size();
    }

    private void append(Item item) {
        items.add(item);
        onNewContent();
    }

    private void onNewContent() {
        if (follow.onNewContent(atBottom()) == ScrollFollow.Action.FOLLOW) {
            list.scrollTo(items.size() - 1);
        }
        newMessages.setVisible(follow.hasPendingContent());
    }

    private void scrollToEnd() {
        list.scrollTo(items.size() - 1);
        follow.reachedBottom();
        newMessages.setVisible(false);
    }

    private boolean atBottom() {
        if (items.size() <= 1 || !(list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) {
            return true;
        }
        var last = flow.getLastVisibleCell();
        return last == null || last.getIndex() >= items.size() - 2;
    }

    private static Label placeholder() {
        Label empty = new Label("Nenhuma mensagem ainda. Escreva abaixo para começar.");
        empty.getStyleClass().add("muted");
        return empty;
    }

    /** Célula de mensagem, reaproveitada pela lista virtualizada. */
    private final class MessageCell extends ListCell<Item> {

        private final Label avatar = new Label();
        private final Label author = new Label();
        private final Label time = new Label();
        private final Label body = new Label();
        private final Label meta = new Label();
        private final HBox root;

        MessageCell() {
            avatar.getStyleClass().add("avatar");
            author.getStyleClass().add("message-author");
            time.getStyleClass().add("message-time");
            body.getStyleClass().add("message-body");
            body.setWrapText(true);
            meta.getStyleClass().add("message-meta");
            meta.setWrapText(true);
            HBox byline = new HBox(8, author, time);
            byline.setAlignment(Pos.BASELINE_LEFT);
            VBox column = new VBox(4, byline, body, meta);
            HBox.setHgrow(column, Priority.ALWAYS);
            root = new HBox(12, avatar, column);
            root.setPadding(new Insets(10, 24, 10, 24));
            // Largura de leitura: a célula acompanha a lista, e o texto quebra dentro dela.
            body.maxWidthProperty().bind(list.widthProperty().subtract(120));
            meta.maxWidthProperty().bind(list.widthProperty().subtract(120));
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            body.textProperty().unbind();
            meta.textProperty().unbind();
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            root.getStyleClass().setAll("message", "message-" + item.role);
            avatar.setText(switch (item.role) {
                case "user" -> "V";
                case "assistant" -> "Z";
                case "error" -> "!";
                default -> "i";
            });
            author.setText(item.author);
            time.setText(item.role.equals("notice") ? "" : item.time);
            body.textProperty().bind(item.text);
            meta.textProperty().bind(item.meta);
            javafx.beans.binding.BooleanBinding shown = item.meta.isNotEmpty()
                    .and(javafx.beans.binding.Bindings.createBooleanBinding(technical::get, technical));
            meta.visibleProperty().bind(shown);
            meta.managedProperty().bind(shown);
            setGraphic(root);
        }
    }
}
