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

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;
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
 */
@Spec("SPEC-011")
public final class SpeechPlayer implements NarrationSink, AutoCloseable {

    static final Duration LEAD = Duration.ofMillis(300);
    static final Duration CHUNK = Duration.ofMillis(100);
    static final int QUEUE = 3;

    /** Envia um frame binário a uma sessão. */
    @FunctionalInterface
    public interface BinarySender {
        boolean send(String sessionId, BinaryFrame frame);
    }

    private static final Logger log = LoggerFactory.getLogger(SpeechPlayer.class);
    private static final Object END = new Object();
    /** O tom de escuta entra na fila como uma narração marcada; não é sintetizado. */
    static final Narration CUE = new Narration("tom de escuta", Narration.Priority.AUTHORIZATION, "cue");
    static final int CUE_RATE = 16_000;
    private static final java.util.regex.Pattern WAKE_WORD =
            java.util.regex.Pattern.compile("z[oóô]rd[oõ]", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final VoiceEngine engine;
    private final VoiceService voice;
    private final ClientRequests clients;
    private final BinarySender binary;
    private final LinkedBlockingDeque<Narration> queue = new LinkedBlockingDeque<>();
    private volatile Narration current;
    private volatile boolean interrupted;
    private volatile String interruption;
    private volatile boolean running;
    private Thread worker;
    private long lastStream;
    private long lastSpeech;

    public SpeechPlayer(VoiceEngine engine, VoiceService voice, ClientRequests clients, BinarySender binary) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.binary = Objects.requireNonNull(binary, "binary");
    }

    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("zordon-speech").start(this::loop);
    }

    @Override
    public synchronized void speak(Narration narration) {
        if (narration.priority() == Narration.Priority.AUTHORIZATION) {
            Narration playing = current;
            if (playing != null && playing.priority() == Narration.Priority.NORMAL) {
                interrupted = true;
            }
            queue.removeIf(queued -> queued.priority() == Narration.Priority.NORMAL);
            queue.addFirst(narration);
            return;
        }
        if (queue.size() >= QUEUE) {
            // Cheia: uma normal sai para a mais nova entrar; se só há urgentes, a normal nova é que sobra.
            Iterator<Narration> oldest = queue.iterator();
            boolean freed = false;
            while (oldest.hasNext()) {
                if (oldest.next().priority() == Narration.Priority.NORMAL) {
                    oldest.remove();
                    freed = true;
                    break;
                }
            }
            if (!freed && narration.priority() == Narration.Priority.NORMAL) {
                return;
            }
        }
        queue.addLast(narration);
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
        queue.remove(CUE);
        queue.addFirst(CUE);
    }

    /** O tom: duas notas subindo, 120 ms, baixo. Gerado aqui, sem motor. */
    static byte[] cueTone() {
        int samples = CUE_RATE * 120 / 1000;
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double t = (double) i / CUE_RATE;
            double frequency = i < samples / 2 ? 660 : 990;
            double envelope = Math.min(1, Math.min(i, samples - i) / (CUE_RATE * 0.01));
            short value = (short) (Math.sin(2 * Math.PI * frequency * t) * envelope * 0.22 * Short.MAX_VALUE);
            pcm[2 * i] = (byte) value;
            pcm[2 * i + 1] = (byte) (value >> 8);
        }
        return pcm;
    }

    /** A fala contém a palavra: tocá-la não pode acordar o próprio Zordon (SPEC-013 CA-9). */
    static boolean mentionsWakeWord(String text) {
        return WAKE_WORD.matcher(text).find();
    }

    private void loop() {
        while (running) {
            try {
                Narration next = queue.takeFirst();
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
        if (host == null) {
            return;
        }
        int stream = (int) (++lastStream % 0xFFFF) + 1;
        Map<String, Object> format = Map.of("rate", CUE_RATE, "channels", 1, "encoding", "s16le");
        try {
            Object accepted = clients.request(host, "audio.play", Map.of("streamId", stream, "format", format),
                    Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS).get("accepted");
            if (!Boolean.TRUE.equals(accepted)) {
                return;
            }
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            log.debug("tom de escuta não tocado: {}", e.getMessage());
            return;
        }
        binary.send(host, new BinaryFrame(FrameType.AUDIO_OUT, stream, 0, cueTone()));
        binary.send(host, BinaryFrame.end(stream, 1));
    }

    private void play(Narration narration) throws InterruptedException {
        String host = voice.playbackHost().orElse(null);
        if (host == null || engine.status().state() != VoiceEngine.State.READY) {
            log.debug("fala não tocada: {}", host == null ? "nenhum host toca áudio" : "motor não está pronto");
            return;
        }
        BlockingQueue<Object> audio = new LinkedBlockingQueue<>();
        int[] rate = {0};
        long id = ++lastSpeech;
        boolean started = engine.speak(id, narration.text(), new VoiceEngine.Speech() {
            @Override
            public void chunk(int sampleRate, byte[] pcm) {
                rate[0] = sampleRate;
                audio.add(pcm);
            }

            @Override
            public void end(String reason) {
                audio.add(END);
            }
        });
        if (!started) {
            return;
        }
        Object first = audio.poll(10, TimeUnit.SECONDS);
        if (!(first instanceof byte[])) {
            return;
        }
        int stream = (int) (++lastStream % 0xFFFF) + 1;
        Map<String, Object> format = Map.of("rate", rate[0], "channels", 1, "encoding", "s16le");
        Object accepted;
        try {
            accepted = clients.request(host, "audio.play", Map.of("streamId", stream, "format", format),
                    Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS).get("accepted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            log.warn("host não aceitou tocar a fala: {}", e.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(accepted)) {
            log.warn("host recusou tocar a fala");
            return;
        }
        voice.speaking(true, !mentionsWakeWord(narration.text()));
        long startedAt = System.nanoTime();
        long sentBytes = 0;
        long seq = 0;
        int bytesPerSecond = rate[0] * 2;
        int step = (int) (bytesPerSecond * CHUNK.toMillis() / 1000) & ~1;
        Object next = first;
        while (next instanceof byte[] pcm) {
            for (int offset = 0; offset < pcm.length; offset += step) {
                if (interrupted) {
                    clients.request(host, "audio.stop", Map.of("streamId", stream), Duration.ofSeconds(2));
                    String why = interruption == null ? "uma autorização" : interruption;
                    interruption = null;
                    log.info("fala interrompida por {}", why);
                    return;
                }
                // Espera a reprodução alcançar: no máximo LEAD de áudio à frente do relógio.
                long aheadNanos = sentBytes * 1_000_000_000L / bytesPerSecond - (System.nanoTime() - startedAt);
                if (aheadNanos > LEAD.toNanos()) {
                    TimeUnit.NANOSECONDS.sleep(aheadNanos - LEAD.toNanos());
                }
                byte[] slice = java.util.Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + step));
                binary.send(host, new BinaryFrame(FrameType.AUDIO_OUT, stream, seq++, slice));
                sentBytes += slice.length;
            }
            next = audio.poll(10, TimeUnit.SECONDS);
        }
        binary.send(host, BinaryFrame.end(stream, seq));
        long remaining = sentBytes * 1_000_000_000L / bytesPerSecond - (System.nanoTime() - startedAt);
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
        log.info("fala tocada: {} ({} ms)", narration.category(), sentBytes * 1000 / bytesPerSecond);
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }
}
