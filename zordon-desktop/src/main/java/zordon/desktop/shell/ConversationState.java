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
package zordon.desktop.shell;

import java.util.Map;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import zordon.zwp.CoreConnection;

/** A conversa: a sessão, o turno em andamento, o consumo e o rascunho do compositor. */
public final class ConversationState {

    private final StringProperty sessionId = new SimpleStringProperty();
    private final ObjectProperty<TurnSummary> lastTurn = new SimpleObjectProperty<>();
    private final ObjectProperty<SessionUsage> usage = new SimpleObjectProperty<>(SessionUsage.NONE);
    private final BooleanProperty turnRunning = new SimpleBooleanProperty();
    private final StringProperty currentTurnId = new SimpleStringProperty();
    private final StringProperty draft = new SimpleStringProperty("");
    private final StringBinding composerBlockedReason;

    ConversationState(ObservableValue<CoreConnection.State> connection) {
        this.composerBlockedReason = Bindings.createStringBinding(
                () -> switch (connection.getValue()) {
                    case ONLINE -> "";
                    case CONNECTING -> "Aguardando o núcleo…";
                    case OFFLINE -> "Núcleo offline — reconectando. O rascunho fica guardado.";
                },
                connection);
    }

    void response(Map<String, Object> payload) {
        TurnSummary.fromResponse(payload).ifPresent(turn -> {
            turnRunning.set(false); lastTurn.set(turn); usage.set(usage.get().plus(turn));
        });
    }

    public StringProperty sessionIdProperty() {
        return sessionId;
    }

    public ObjectProperty<TurnSummary> lastTurnProperty() {
        return lastTurn;
    }

    public ObjectProperty<SessionUsage> usageProperty() {
        return usage;
    }

    public BooleanProperty turnRunningProperty() {
        return turnRunning;
    }

    /** O turno em andamento — é ele que "Cancelar tarefa" cancela. */
    public StringProperty currentTurnIdProperty() {
        return currentTurnId;
    }

    public StringProperty draftProperty() {
        return draft;
    }

    /** Vazio quando dá para enviar; senão, o motivo, que o compositor mostra. */
    public StringBinding composerBlockedReason() {
        return composerBlockedReason;
    }
}
