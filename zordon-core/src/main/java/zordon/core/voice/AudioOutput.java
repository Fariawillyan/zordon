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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;
import zordon.core.zwp.ClientRequests;

/**
 * O áudio no host: {@code audio.play}, frames {@code AUDIO_OUT} no ritmo da
 * reprodução e {@code AUDIO_END}. O host nunca acumula mais que {@link #LEAD}.
 */
final class AudioOutput {

    static final Duration LEAD = Duration.ofMillis(300);
    static final Duration CHUNK = Duration.ofMillis(100);
    static final int CUE_RATE = 16_000;

    private static final Logger log = LoggerFactory.getLogger(SpeechPlayer.class);

    private final ClientRequests clients;
    private final SpeechPlayer.BinarySender binary;
    private long lastStream;

    AudioOutput(ClientRequests clients, SpeechPlayer.BinarySender binary) {
        this.clients = clients;
        this.binary = binary;
    }

    /** O tom curto de escuta. */
    void cue(String host) throws InterruptedException {
        int stream = nextStream();
        Map<String, Object> format = Map.of("rate", CUE_RATE, "channels", 1, "encoding", "s16le");
        try {
            Object accepted = clients.request(host, "audio.play", Map.of("streamId", stream, "format", format),
                    Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS).get("accepted");
            if (!Boolean.TRUE.equals(accepted)) {
                return;
            }
        } catch (ExecutionException | TimeoutException e) {
            log.debug("tom de escuta não tocado: {}", e.getMessage());
            return;
        }
        binary.send(host, new BinaryFrame(FrameType.AUDIO_OUT, stream, 0, cueTone()));
        binary.send(host, BinaryFrame.end(stream, 1));
    }

    /** Pede ao host para tocar uma fala. @return o stream aceito, ou {@code -1} */
    int open(String host, int rate) throws InterruptedException {
        int stream = nextStream();
        Map<String, Object> format = Map.of("rate", rate, "channels", 1, "encoding", "s16le");
        Object accepted;
        try {
            accepted = clients.request(host, "audio.play", Map.of("streamId", stream, "format", format),
                    Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS).get("accepted");
        } catch (ExecutionException | TimeoutException e) {
            log.warn("host não aceitou tocar a fala: {}", e.getMessage());
            return -1;
        }
        if (!Boolean.TRUE.equals(accepted)) {
            log.warn("host recusou tocar a fala");
            return -1;
        }
        return stream;
    }

    /**
     * Manda a fala no ritmo da reprodução e espera ela terminar de tocar.
     *
     * @return os milissegundos tocados, ou {@code -1} se foi interrompida — e aí o host já recebeu {@code audio.stop}
     */
    long play(String host, int stream, byte[] first, SynthesizedAudio audio, BooleanSupplier interrupted)
            throws InterruptedException {
        long startedAt = System.nanoTime();
        long sentBytes = 0;
        long seq = 0;
        int bytesPerSecond = audio.rate() * 2;
        int step = (int) (bytesPerSecond * CHUNK.toMillis() / 1000) & ~1;
        byte[] pcm = first;
        while (pcm != null) {
            for (int offset = 0; offset < pcm.length; offset += step) {
                if (interrupted.getAsBoolean()) {
                    clients.request(host, "audio.stop", Map.of("streamId", stream), Duration.ofSeconds(2));
                    return -1;
                }
                // Espera a reprodução alcançar: no máximo LEAD de áudio à frente do relógio.
                long aheadNanos = sentBytes * 1_000_000_000L / bytesPerSecond - (System.nanoTime() - startedAt);
                if (aheadNanos > LEAD.toNanos()) {
                    TimeUnit.NANOSECONDS.sleep(aheadNanos - LEAD.toNanos());
                }
                byte[] slice = Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + step));
                binary.send(host, new BinaryFrame(FrameType.AUDIO_OUT, stream, seq++, slice));
                sentBytes += slice.length;
            }
            pcm = audio.next();
        }
        binary.send(host, BinaryFrame.end(stream, seq));
        long remaining = sentBytes * 1_000_000_000L / bytesPerSecond - (System.nanoTime() - startedAt);
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
        return sentBytes * 1000 / bytesPerSecond;
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

    private int nextStream() {
        return (int) (++lastStream % 0xFFFF) + 1;
    }
}
