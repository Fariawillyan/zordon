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

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;

/**
 * Toca a fala do Zordon na saída padrão do Windows (SPEC-011 §3): um stream por
 * vez, anunciado por {@code audio.play}, alimentado por {@code AUDIO_OUT} e
 * encerrado por {@code AUDIO_END} (toca até o fim) ou {@code audio.stop} (corta).
 *
 * <p>A placa é escrita numa thread própria: a thread do socket nunca espera o
 * áudio tocar.
 */
@Spec("SPEC-011")
public final class Speaker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Speaker.class);
    /** Fim normal: toca o que falta e fecha. */
    private static final byte[] END = new byte[0];
    /** Corte: fecha sem tocar o resto (audio.stop, conexão perdida). */
    private static final byte[] ABORT = new byte[0];

    private final SoundSystem sound;
    private Stream current;

    private record Stream(int id, SoundSystem.PlaybackLine line, BlockingQueue<byte[]> queue) {}

    public Speaker(SoundSystem sound) {
        this.sound = Objects.requireNonNull(sound, "sound");
    }

    /** @return se a saída abriu. Um stream novo corta o anterior. */
    public synchronized boolean play(int streamId, int rate) {
        stopCurrent();
        SoundSystem.PlaybackLine line;
        try {
            line = sound.openPlayback(rate);
        } catch (Exception e) {
            log.warn("saída de áudio não abriu: {}", e.getMessage());
            return false;
        }
        Stream stream = new Stream(streamId, line, new LinkedBlockingQueue<>());
        current = stream;
        Thread.ofPlatform().daemon().name("zordon-fala-" + streamId).start(() -> drain(stream));
        return true;
    }

    public synchronized void frame(BinaryFrame frame) {
        Stream stream = current;
        if (stream == null || stream.id() != frame.streamId()) {
            return;
        }
        if (frame.type() == FrameType.AUDIO_OUT) {
            stream.queue().add(frame.payload());
        } else if (frame.type() == FrameType.AUDIO_END) {
            stream.queue().add(END);
            current = null;
        }
    }

    /** Corta na hora. */
    public synchronized void stop(int streamId) {
        if (current != null && current.id() == streamId) {
            stopCurrent();
        }
    }

    public synchronized boolean playing() {
        return current != null;
    }

    @Override
    public synchronized void close() {
        stopCurrent();
    }

    private void stopCurrent() {
        Stream stream = current;
        current = null;
        if (stream != null) {
            stream.queue().add(ABORT);
            stream.line().close();
        }
    }

    private void drain(Stream stream) {
        try {
            while (true) {
                byte[] pcm = stream.queue().take();
                if (pcm == ABORT) {
                    break;
                }
                if (pcm == END) {
                    stream.line().drain();
                    break;
                }
                stream.line().write(pcm);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("fala cortada: {}", e.toString());
        } finally {
            stream.line().close();
        }
    }
}
