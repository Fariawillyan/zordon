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
package zordon.core.voice;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import zordon.api.trace.Spec;

/**
 * O motor de voz de verdade: o sidecar {@code zordon-voice} num socket Unix
 * (SPEC-011, SPEC-013, ADR-0028). O núcleo é o cliente e reconecta sozinho; enquanto o
 * socket não existe, o motor está "não instalado"; conectado, "carregando" até o
 * sidecar dizer que aqueceu os modelos.
 *
 * <p>A moldura fica em {@link EngineFrames}; a conexão, em {@link EngineLink}; o
 * que espera resposta, em {@link PendingVoiceCalls}.
 */
@Spec("SPEC-011")
public final class SidecarVoiceEngine implements VoiceEngine, AutoCloseable {

    public static final String NOT_INSTALLED = "motor de voz não instalado";
    public static final String LOADING = "o motor de voz está carregando";

    private final PendingVoiceCalls calls = new PendingVoiceCalls();
    private final EngineLink link;

    public SidecarVoiceEngine(Path socket) {
        this.link = new EngineLink(Objects.requireNonNull(socket, "socket"), calls);
    }

    public void start() {
        link.start();
    }

    @Override
    public Status status() {
        return link.status();
    }

    @Override
    public Activity activity() {
        return Activity.IDLE;
    }

    @Override
    public void onChange(Runnable listener) {
        link.onChange(listener);
    }

    @Override
    public boolean wakeWord() {
        return link.wakeWord();
    }

    @Override
    public boolean stream(long id, StreamMode mode, Stream listener) {
        if (!link.ready()) {
            return false;
        }
        calls.stream(id, listener);
        if (!link.send(Map.of("op", "stream", "id", id, "mode", mode.wire()), null)) {
            calls.forgetStream(id);
            return false;
        }
        return true;
    }

    @Override
    public void duplex(long id, boolean speaking, boolean armed) {
        if (calls.streaming(id)) {
            link.send(Map.of("op", "duplex", "id", id, "speaking", speaking, "armed", armed), null);
        }
    }

    @Override
    public void listenNow(long id) {
        if (calls.streaming(id)) {
            link.send(Map.of("op", "listen_now", "id", id), null);
        }
    }

    @Override
    public boolean listen(long id, Listening listener) {
        if (!link.ready()) {
            return false;
        }
        calls.listen(id, listener);
        if (!link.send(Map.of("op", "listen", "id", id), null)) {
            calls.forgetListen(id);
            return false;
        }
        return true;
    }

    @Override
    public void audio(long id, byte[] pcm) {
        if (calls.listening(id) || calls.streaming(id)) {
            link.send(Map.of("op", "audio", "id", id), pcm);
        }
    }

    @Override
    public void stop(long id) {
        link.send(Map.of("op", "stop", "id", id), null);
    }

    @Override
    public void cancel(long id) {
        calls.forgetListen(id);
        calls.forgetStream(id);
        link.send(Map.of("op", "cancel", "id", id), null);
    }

    @Override
    public boolean speak(long id, String text, Speech speech) {
        return speak(id, text, SpeechStyle.normal(), speech);
    }

    @Override
    public boolean speak(long id, String text, SpeechStyle style, Speech speech) {
        if (!link.ready()) {
            return false;
        }
        calls.speak(id, speech);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", "speak");
        request.put("id", id);
        request.put("text", text);
        request.put("style", style == null ? SpeechStyle.normal().profile() : style.profile());
        if (!link.send(request, null)) {
            calls.forgetSpeech(id);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        link.close();
    }
}
