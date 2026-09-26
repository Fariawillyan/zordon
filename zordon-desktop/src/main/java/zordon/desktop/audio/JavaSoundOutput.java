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
package zordon.desktop.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/** A saída do Java Sound: a linha estéreo de 16 bits e o nome do dispositivo que vai tocar. */
final class JavaSoundOutput {

    private JavaSoundOutput() {}

    static String name() {
        DataLine.Info wanted = new DataLine.Info(SourceDataLine.class, format());
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(wanted)) {
                return info.getName();
            }
        }
        return "";
    }

    static boolean inWsl() {
        return System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null;
    }

    static SoundPlayer.Output open() throws Exception {
        AudioFormat audio = format();
        String name = name();
        SourceDataLine line = AudioSystem.getSourceDataLine(audio);
        try {
            line.open(audio, 4096);
        } catch (Exception failure) {
            line.close();
            throw failure;
        }
        return new SoundPlayer.Output() {
            @Override public String name() { return name.isEmpty() ? "saída padrão" : name; }
            @Override public void start() { line.start(); }
            @Override public int write(byte[] data, int offset, int length) { return line.write(data, offset, length); }
            @Override public long framePosition() { return line.getLongFramePosition(); }
            @Override public void drain() { line.drain(); }
            @Override public void close() { line.stop(); line.flush(); line.close(); }
        };
    }

    private static AudioFormat format() {
        return new AudioFormat(SoundSynthesizer.SAMPLE_RATE, 16, 2, true, false);
    }
}
