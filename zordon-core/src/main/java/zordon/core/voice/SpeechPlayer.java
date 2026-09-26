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

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.core.activity.Narration;
import zordon.core.activity.NarrationSink;
import zordon.core.zwp.ClientRequests;

/**
 * A voz do Zordon (SPEC-011 §3, SPEC-012): cada fala que o narrador decide é
 * sintetizada pelo motor e tocada no host, com {@code audio.play}, frames
 * {@code AUDIO_OUT} e {@code AUDIO_END}.
 *
 * <p>Fila de no máximo 3; autorização passa na frente e interrompe uma fala
 * normal em curso. O áudio sai no ritmo da reprodução, com 300 ms de folga: o
 * host nunca acumula mais que isso (o crédito do áudio de saída vem com o
 * barge-in).
 *
 * <p>A fila fica em {@link NarrationQueue}; o protocolo com o host, em {@link AudioOutput}.
 */
@Spec("SPEC-011")
public final class SpeechPlayer implements NarrationSink, AutoCloseable {

    static final int QUEUE = 3;

    /** Envia um frame binário a uma sessão. */
    @FunctionalInterface
    public interface BinarySender {
        boolean send(String sessionId, BinaryFrame frame);
    }

    private static final Logger log = LoggerFactory.getLogger(SpeechPlayer.class);
    /** O tom de escuta entra na fila como uma narração marcada; não é sintetizado. */
    static final Narration CUE = new Narration("tom de escuta", Narration.Priority.AUTHORIZATION, "cue");

    private final VoiceEngine engine;
    private final VoiceService voice;
    private final AudioOutput output;
    private final NarrationQueue queue = new NarrationQueue();
    private volatile Narration current;
    private volatile boolean interrupted;
    private volatile String interruption;
    private volatile boolean running;
    private Thread worker;
    private long lastSpeech;

    public SpeechPlayer(VoiceEngine engine, VoiceService voice, ClientRequests clients, BinarySender binary) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.output = new AudioOutput(Objects.requireNonNull(clients, "clients"),
                Objects.requireNonNull(binary, "binary"));
    }

    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("zordon-speech").start(this::loop);
    }

    @Override
    public synchronized void speak(Narration narration) {
        if (queue.offer(narration, current)) {
            interrupted = true;
        }
    }

    int queued() {
        return queue.size();
    }

    /**
     * Barge-in (SPEC-013 §3): a fala em curso para, a placa esvazia e nada do que
     * estava na fila é dito. O usuário vai falar; o que ele perdeu vira o próximo turno.
     */
    public synchronized void interrupt() {
        queue.clear();
        if (current != null && current != CUE) {
            interruption = "o usuário falou por cima";
            interrupted = true;
        }
    }

    /** Toca o tom curto de escuta, na frente de tudo. Não marca "falando": o comando vem logo em seguida. */
    public synchronized void cue() {
        queue.first(CUE);
    }

    private void loop() {
        while (running) {
            try {
                Narration next = queue.take();
                current = next;
                interrupted = false;
                if (next == CUE) {
                    playCue();
                } else {
                    play(next);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("fala interrompida por erro: {}", e.toString());
            } finally {
                current = null;
                voice.speaking(false);
            }
        }
    }

    private void playCue() throws InterruptedException {
        String host = voice.playbackHost().orElse(null);
        if (host != null) {
            output.cue(host);
        }
    }

    private void play(Narration narration) throws InterruptedException {
        String host = voice.playbackHost().orElse(null);
        if (host == null || engine.status().state() != VoiceEngine.State.READY) {
            log.debug("fala não tocada: {}", host == null ? "nenhum host toca áudio" : "motor não está pronto");
            return;
        }
        SynthesizedAudio audio = new SynthesizedAudio();
        if (!engine.speak(++lastSpeech, narration.text(), SpokenNarration.style(narration), audio)) {
            return;
        }
        byte[] first = audio.next();
        if (first == null) {
            return;
        }
        int stream = output.open(host, audio.rate());
        if (stream < 0) {
            return;
        }
        voice.speaking(true, !SpokenNarration.mentionsWakeWord(narration.text()));
        long played = output.play(host, stream, first, audio, () -> interrupted);
        if (played < 0) {
            String why = interruption == null ? "uma autorização" : interruption;
            interruption = null;
            log.info("fala interrompida por {}", why);
            return;
        }
        log.info("fala tocada: {} ({} ms)", narration.category(), played);
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }
}
