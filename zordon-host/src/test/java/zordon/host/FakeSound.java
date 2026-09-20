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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Sistema de som falso: abre linhas que entregam frames quando o teste manda. */
final class FakeSound implements SoundSystem {

    final List<Device> devices = List.of(
            new Device(DEFAULT, "Padrão do Windows", true),
            new Device("usb", "Microfone USB", false));
    final List<Line> opened = new CopyOnWriteArrayList<>();
    volatile String failWith;

    @Override
    public List<Device> devices() {
        return devices;
    }

    @Override
    public CaptureLine open(String deviceId) {
        if (failWith != null) {
            throw new IllegalStateException(failWith);
        }
        Line line = new Line(deviceId);
        opened.add(line);
        return line;
    }

    final List<Playback> playbacks = new CopyOnWriteArrayList<>();
    volatile String playbackFails;

    @Override
    public PlaybackLine openPlayback(int rate) {
        if (playbackFails != null) {
            throw new IllegalStateException(playbackFails);
        }
        Playback playback = new Playback(rate);
        playbacks.add(playback);
        return playback;
    }

    /** Saída falsa: guarda o que foi escrito, em ordem. */
    static final class Playback implements PlaybackLine {

        final int rate;
        final java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        volatile boolean drained;
        volatile boolean closed;

        Playback(int rate) {
            this.rate = rate;
        }

        @Override
        public synchronized void write(byte[] pcm) {
            written.writeBytes(pcm);
        }

        @Override
        public void drain() {
            drained = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    Line last() {
        return opened.getLast();
    }

    static final class Line implements CaptureLine {

        final String device;
        volatile boolean closed;
        private volatile boolean ended;
        private final Semaphore frames = new Semaphore(0);

        Line(String device) {
            this.device = device;
        }

        /** O dispositivo entrega {@code count} frames. */
        void deliver(int count) {
            frames.release(count);
        }

        /** O dispositivo some: a próxima leitura volta vazia. */
        void end() {
            ended = true;
            frames.release();
        }

        @Override
        public int read(byte[] buffer) {
            try {
                while (!frames.tryAcquire(20, TimeUnit.MILLISECONDS)) {
                    if (closed) {
                        return -1;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            return closed || ended ? 0 : buffer.length;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
