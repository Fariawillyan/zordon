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

import java.time.Instant;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import zordon.zwp.CoreConnection;

/** A conexão com o núcleo: o estado, o detalhe, a última sincronização e as quedas. */
public final class ConnectionState {

    private final ObjectProperty<CoreConnection.State> state =
            new SimpleObjectProperty<>(CoreConnection.State.CONNECTING);
    private final StringProperty detail = new SimpleStringProperty("procurando o núcleo");
    private final ObjectProperty<Instant> lastSync = new SimpleObjectProperty<>();
    private final IntegerProperty reconnections = new SimpleIntegerProperty();
    private final StringBinding label;
    private boolean everConnected;

    ConnectionState() {
        this.label = Bindings.createStringBinding(
                () -> switch (state.get()) {
                    case ONLINE -> "Núcleo conectado";
                    case CONNECTING -> "Conectando…";
                    case OFFLINE -> "Núcleo offline";
                },
                state);
    }

    void online(String coreVersion, Instant now) {
        everConnected = true;
        state.set(CoreConnection.State.ONLINE);
        detail.set("WSL · núcleo " + coreVersion);
        lastSync.set(now);
    }

    /** Conta a queda só se já esteve conectado e ainda não estava offline. */
    void offline(String reason) {
        if (everConnected && state.get() != CoreConnection.State.OFFLINE) {
            reconnections.set(reconnections.get() + 1);
        }
        state.set(CoreConnection.State.OFFLINE);
        detail.set(reason);
    }

    public ObjectProperty<CoreConnection.State> stateProperty() {
        return state;
    }

    public StringProperty detailProperty() {
        return detail;
    }

    public ObjectProperty<Instant> lastSyncProperty() {
        return lastSync;
    }

    public IntegerProperty reconnectionsProperty() {
        return reconnections;
    }

    /** O estado em palavras, para o indicador do núcleo. */
    public StringBinding label() {
        return label;
    }
}
