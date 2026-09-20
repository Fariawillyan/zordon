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
package zordon.host;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import zordon.api.trace.Spec;
import zordon.api.zwp.ZwpError;
import zordon.api.zwp.ZwpErrorKind;
import zordon.zwp.CoreConnection;
import zordon.zwp.ZwpRemoteException;

/** Os três métodos de áudio que o núcleo pede ao host (SPEC-006 §7, SPEC-007 §7). */
@Spec("SPEC-007")
public final class AudioMethods {

    private final Microphone microphone;
    private final StreamingSink stream;
    private Speaker speaker;

    public AudioMethods(Microphone microphone) {
        this(microphone, null);
    }

    /** @param stream para onde o áudio vai; {@code null} descarta (SPEC-007). */
    public AudioMethods(Microphone microphone, StreamingSink stream) {
        this.microphone = microphone;
        this.stream = stream;
    }

    public void registerOn(CoreConnection connection) {
        connection.handle("audio.setCaptureEnabled", this::setCaptureEnabled)
                .handle("audio.listDevices", params -> listDevices())
                .handle("audio.selectDevice", this::selectDevice)
                .onNotification("audio.credit", this::credit)
                .handle("audio.play", this::play)
                .handle("audio.stop", this::stopPlayback);
    }

    /** Liga a reprodução da fala (SPEC-011); sem ela, {@code audio.play} recusa. */
    public AudioMethods speaker(Speaker target) {
        this.speaker = target;
        return this;
    }

    Map<String, Object> play(Map<String, Object> params) {
        if (!(params.get("streamId") instanceof Number id)) {
            throw invalid("params.streamId é obrigatório");
        }
        int rate = params.get("format") instanceof Map<?, ?> format && format.get("rate") instanceof Number r
                ? r.intValue() : 22_050;
        return Map.of("accepted", speaker != null && speaker.play(id.intValue(), rate));
    }

    Map<String, Object> stopPlayback(Map<String, Object> params) {
        if (speaker != null && params.get("streamId") instanceof Number id) {
            speaker.stop(id.intValue());
        }
        return Map.of();
    }

    void credit(Map<String, Object> params) {
        if (stream != null && params.get("streamId") instanceof Number id && params.get("frames") instanceof Number frames) {
            stream.grant(id.intValue(), frames.intValue());
        }
    }

    /** Responde o estado da linha depois da operação, e o motivo quando difere do pedido. */
    Map<String, Object> setCaptureEnabled(Map<String, Object> params) {
        if (!(params.get("enabled") instanceof Boolean wanted)) {
            throw invalid("params.enabled precisa ser true ou false");
        }
        if (wanted) {
            // O stream começa antes da linha: o primeiro frame já tem para onde ir.
            if (stream != null && params.get("streamId") instanceof Number id) {
                stream.begin(id.intValue());
            }
            if (!microphone.enable() && stream != null) {
                stream.abort();
            }
        } else {
            microphone.disable();
            if (stream != null) {
                stream.end();
            }
        }
        boolean actual = microphone.capturing();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", actual);
        if (actual != wanted) {
            result.put("reason", microphone.lastFailure()
                    .orElse(wanted ? "o microfone não ligou" : "o microfone não desligou"));
        }
        return result;
    }

    Map<String, Object> listDevices() {
        List<Map<String, Object>> devices = microphone.devices().stream()
                .map(device -> Map.<String, Object>of("id", device.id(), "name", device.name(), "default", device.isDefault()))
                .toList();
        return Map.of("devices", devices, "selected", microphone.selected());
    }

    Map<String, Object> selectDevice(Map<String, Object> params) {
        if (!(params.get("deviceId") instanceof String id) || id.isBlank()) {
            throw invalid("params.deviceId é obrigatório");
        }
        try {
            SoundSystem.Device device = microphone.select(id);
            return Map.of("selected", device.id(), "name", device.name());
        } catch (Microphone.DeviceNotFound e) {
            throw new ZwpRemoteException(ZwpError.of(ZwpErrorKind.ERR_NOT_FOUND, e.getMessage()));
        }
    }

    private static ZwpRemoteException invalid(String message) {
        return new ZwpRemoteException(ZwpError.of(ZwpErrorKind.ERR_INVALID_ARGUMENT, message));
    }
}
