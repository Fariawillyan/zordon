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
package zordon.core.zwp;

import java.util.Map;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.core.voice.VoiceMode;
import zordon.core.voice.VoiceService;

/**
 * {@code voice.*} para clientes, e a ponte entre sessões de host e o
 * {@link VoiceService} (SPEC-006 §7).
 */
@Spec("SPEC-006")
public final class VoiceMethods {

    /** Capacidade que um host declara no hello para controlar o microfone. */
    public static final String AUDIO_CAPTURE = "audio.capture";
    /** Capacidade que um host declara para tocar a fala do Zordon (SPEC-011). */
    public static final String AUDIO_PLAYBACK = "audio.playback";

    private final VoiceService voice;

    public VoiceMethods(VoiceService voice) {
        this.voice = voice;
    }

    public void registerOn(ZwpServer server) {
        server.register("voice.status", (session, params) -> voice.status())
                .register("voice.setMode", (session, params) -> voice.setMode(VoiceMode.parse(params.get("mode"))))
                .register("voice.testMicrophone", (session, params) -> voice.testMicrophone(seconds(params)))
                .register("voice.startListening", (session, params) -> voice.startListening())
                .register("voice.stopListening", (session, params) -> voice.stopListening())
                .registerAsync("voice.devices", (session, params) -> voice.devices())
                .registerAsync("voice.selectDevice", (session, params) -> voice.selectDevice(
                        params.get("deviceId") instanceof String id ? id : null))
                .onSessionReady(session -> {
                    if (controlsMicrophone(session)) {
                        voice.hostConnected(session.id());
                    }
                    if (session.is(ClientKind.HOST) && session.capabilities().contains(AUDIO_PLAYBACK)) {
                        voice.playbackHostConnected(session.id());
                    }
                })
                .onSessionClosed(session -> voice.hostDisconnected(session.id()));
    }

    /** Padrão de 5 s (SPEC-009 §3); o {@code VoiceService} confere o intervalo. */
    private static int seconds(Map<String, Object> params) {
        Object value = params.get("seconds");
        if (value == null) {
            return 5;
        }
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalArgumentException("seconds precisa ser um inteiro de 1 a 10");
    }

    /**
     * Só um host controla o microfone. Um desktop que declare a capacidade é
     * ignorado: quem decide o que cada tipo de cliente oferece é o núcleo (ZWP §2).
     */
    static boolean controlsMicrophone(ZwpSession session) {
        return session.is(ClientKind.HOST) && session.capabilities().contains(AUDIO_CAPTURE);
    }
}
