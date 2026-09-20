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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O microfone, com as quatro invariantes de SPEC-007 §5: começa desligado; só
 * liga a pedido; {@link #capturing()} diz o estado real da linha depois de cada
 * operação; e nenhum byte capturado sai daqui a não ser pelo {@link FrameSink}.
 */
@Spec("SPEC-007")
public final class Microphone implements AutoCloseable {

    /** 20 ms a 16 kHz, mono, 16 bits. */
    public static final int FRAME_BYTES = 640;
    static final String STOPPED_DELIVERING = "o dispositivo parou de entregar áudio";
    private static final int FRAMES_PER_MINUTE = 3_000;

    /** Id que o host não conhece. */
    public static final class DeviceNotFound extends RuntimeException {

        DeviceNotFound(String id) {
            super("dispositivo de captura desconhecido: " + id);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Microphone.class);

    private final SoundSystem sound;
    private final FrameSink sink;

    // Protegidos por this.
    private String deviceId = SoundSystem.DEFAULT;
    private SoundSystem.CaptureLine line;
    /** Cada linha aberta tem a sua; a thread de leitura de uma linha antiga para sozinha. */
    private long generation;
    private String lastFailure;

    public Microphone(SoundSystem sound, FrameSink sink) {
        this.sound = Objects.requireNonNull(sound, "sound");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** @return se a captura está ligada ao final — nunca "sim" sem a linha iniciada. */
    public synchronized boolean enable() {
        if (line != null) {
            return true;
        }
        try {
            SoundSystem.CaptureLine opened = sound.open(deviceId);
            line = opened;
            lastFailure = null;
            long current = ++generation;
            Thread.ofPlatform().daemon().name("zordon-microfone").start(() -> read(current, opened));
            log.info("microfone ligado");
            return true;
        } catch (Exception e) {
            lastFailure = String.valueOf(e.getMessage());
            log.warn("microfone não ligou: {}", lastFailure);
            return false;
        }
    }

    /** Fecha a linha antes de retornar: quem pergunta depois ouve a verdade. */
    public synchronized void disable() {
        if (line == null) {
            return;
        }
        generation++;
        SoundSystem.CaptureLine closing = line;
        line = null;
        closing.close();
        log.info("microfone desligado");
    }

    public synchronized boolean capturing() {
        return line != null;
    }

    public synchronized String selected() {
        return deviceId;
    }

    public synchronized Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public List<SoundSystem.Device> devices() {
        return sound.devices();
    }

    /**
     * Troca o dispositivo. Com a captura ligada, reabre no novo; se falhar, a
     * captura fica desligada e {@link #lastFailure()} diz por quê.
     */
    public synchronized SoundSystem.Device select(String id) {
        SoundSystem.Device device = sound.devices().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new DeviceNotFound(id));
        boolean wasCapturing = line != null;
        if (wasCapturing) {
            disable();
        }
        deviceId = id;
        log.debug("dispositivo de captura: {}", device.name());
        if (wasCapturing) {
            enable();
        }
        return device;
    }

    @Override
    public void close() {
        disable();
    }

    private void read(long current, SoundSystem.CaptureLine opened) {
        byte[] frame = new byte[FRAME_BYTES];
        long delivered = 0;
        while (true) {
            int length = opened.read(frame);
            synchronized (this) {
                if (current != generation) {
                    return;
                }
            }
            if (length <= 0) {
                deviceStopped(current);
                return;
            }
            sink.accept(frame, length);
            if (++delivered % FRAMES_PER_MINUTE == 0) {
                log.debug("microfone: {} frames lidos", delivered);
            }
        }
    }

    private synchronized void deviceStopped(long current) {
        if (current != generation) {
            return;
        }
        lastFailure = STOPPED_DELIVERING;
        log.warn("{}; microfone desligado", STOPPED_DELIVERING);
        disable();
    }
}
