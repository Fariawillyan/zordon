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

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * A voz como a janela a mostra: o estado confirmado pelo núcleo, o nível do
 * microfone, o estado visual que o console anima, os dispositivos e as
 * transcrições da sessão.
 */
public final class VoiceState {

    static final int TRANSCRIPTIONS_KEPT = 20;

    private final ObjectProperty<VoiceStatus> status = new SimpleObjectProperty<>();
    /** Último {@code VOICE_LEVEL} em dBFS: {@code [rms, pico, graves, médios, agudos]}. */
    private final ObjectProperty<double[]> level = new SimpleObjectProperty<>();
    /** Estado visual do núcleo, de {@code ACTIVITY_STATE} (SPEC-012). */
    private final StringProperty activity = new SimpleStringProperty("idle");
    private final StringProperty error = new SimpleStringProperty("");
    private final ObservableList<VoiceDevice> devices = FXCollections.observableArrayList();
    private final ObservableList<String> transcriptions = FXCollections.observableArrayList();

    /** Snapshot de {@code voice.status}, {@code voice.setMode} ou {@code VOICE_STATE}. */
    public void apply(Map<String, Object> snapshot) {
        status.set(VoiceStatus.from(snapshot));
        error.set("");
    }

    public void failed(String reason) {
        error.set(reason);
    }

    /** Resposta de {@code voice.devices}. */
    public void devices(Map<String, Object> result) {
        devices.setAll(VoiceDevice.listFrom(result));
    }

    /** Sem núcleo, o estado da voz é desconhecido; não mostrar o último como atual. */
    void clear() {
        status.set(null);
        devices.clear();
    }

    void transcription(Map<String, Object> payload, Clock eventClock) {
        transcriptions.addFirst(DateTimeFormatter.ofPattern("HH:mm")
                .format(LocalTime.now(eventClock)) + " · "
                + (payload.get("text") instanceof String text ? text : "(" + payload.getOrDefault("outcome", "sem texto") + ")")
                + " · confiança " + payload.getOrDefault("confidence", "?"));
        if (transcriptions.size() > TRANSCRIPTIONS_KEPT) transcriptions.remove(TRANSCRIPTIONS_KEPT, transcriptions.size());
    }

    public ObjectProperty<VoiceStatus> statusProperty() {
        return status;
    }

    public ObjectProperty<double[]> levelProperty() {
        return level;
    }

    public StringProperty activityProperty() {
        return activity;
    }

    public StringProperty errorProperty() {
        return error;
    }

    public ObservableList<VoiceDevice> devices() {
        return devices;
    }

    public ObservableList<String> transcriptions() {
        return transcriptions;
    }
}
