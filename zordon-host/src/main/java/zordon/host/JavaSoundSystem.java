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

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** Captura com {@code javax.sound.sampled}, sem dependência nativa (ADR-0009). */
final class JavaSoundSystem implements SoundSystem {

    /** 16 kHz, mono, PCM 16 bits little-endian (docs/specs/voice/design.md §1). */
    static final AudioFormat FORMAT = new AudioFormat(16_000, 16, 1, true, false);

    /** O apelido que o DirectSound dá ao padrão; já representado por {@link #DEFAULT}. */
    private static final String PRIMARY_ALIAS = "Primary Sound Capture Driver";

    private final DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);

    @Override
    public List<Device> devices() {
        List<Device> devices = new ArrayList<>();
        devices.add(new Device(DEFAULT, "Padrão do Windows", true));
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (!PRIMARY_ALIAS.equals(info.getName()) && AudioSystem.getMixer(info).isLineSupported(wanted)) {
                devices.add(new Device(info.getName(), info.getName(), false));
            }
        }
        return devices;
    }

    @Override
    public CaptureLine open(String deviceId) throws Exception {
        TargetDataLine line = DEFAULT.equals(deviceId)
                ? AudioSystem.getTargetDataLine(FORMAT)
                : (TargetDataLine) mixer(deviceId).getLine(wanted);
        try {
            line.open(FORMAT, Microphone.FRAME_BYTES * 8);
            line.start();
        } catch (LineUnavailableException | SecurityException e) {
            line.close();
            throw new IllegalStateException("o Windows não liberou o microfone — em uso por outro aplicativo ou"
                    + " bloqueado em Configurações > Privacidade > Microfone (" + e.getMessage() + ")", e);
        }
        return new CaptureLine() {
            @Override
            public int read(byte[] buffer) {
                return line.read(buffer, 0, buffer.length);
            }

            @Override
            public void close() {
                line.stop();
                line.flush();
                line.close();
            }
        };
    }

    @Override
    public PlaybackLine openPlayback(int rate) throws Exception {
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        javax.sound.sampled.SourceDataLine line = AudioSystem.getSourceDataLine(format);
        line.open(format, rate / 5 * 2);
        line.start();
        return new PlaybackLine() {
            @Override
            public void write(byte[] pcm) {
                line.write(pcm, 0, pcm.length);
            }

            @Override
            public void drain() {
                line.drain();
            }

            @Override
            public void close() {
                line.stop();
                line.flush();
                line.close();
            }
        };
    }

    private static Mixer mixer(String name) {
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(name)) {
                return AudioSystem.getMixer(info);
            }
        }
        throw new IllegalArgumentException("dispositivo não encontrado: " + name);
    }
}
