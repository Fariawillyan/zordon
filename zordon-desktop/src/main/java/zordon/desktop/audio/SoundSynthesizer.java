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

import zordon.api.trace.Spec;

/** Small, deterministic PCM cues. No recordings, files, capture or native media dependency. */
@Spec("SPEC-008")
public final class SoundSynthesizer {
    public static final int SAMPLE_RATE = 44_100;
    public static final int CHANNELS = 2;

    public enum Cue {
        ACTIVATE("Ativação", 392, 784), LISTEN("Escuta", 660, 880),
        THINK("Processamento", 220, 330), RESPOND("Resposta", 523.25, 1046.5),
        COMPLETE("Conclusão", 523.25, 783.99), ALERT("Alerta", 440, 293.66);

        private final String label;
        final double start;
        final double end;

        Cue(String label, double start, double end) {
            this.label = label;
            this.start = start;
            this.end = end;
        }

        @Override public String toString() { return label; }
    }

    public record Settings(boolean echo, boolean reverb, boolean soft, boolean stereo) {
        public static final Settings DEFAULT = new Settings(true, true, false, true);
    }

    public record Sound(float[] samples) {
        public int frames() { return samples.length / CHANNELS; }
        public double seconds() { return frames() / (double) SAMPLE_RATE; }

        public double rms(int frame, double volume) {
            double sum = 0;
            int start = Math.max(0, frame - 512);
            int end = Math.min(frames(), frame);
            if (end <= start) return 0;
            for (int i = start * 2; i < end * 2; i++) sum += samples[i] * samples[i];
            return Math.sqrt(sum / ((end - start) * 2)) * gain(volume);
        }

        public double sample(int frame, double volume) {
            if (frame < 0 || frame >= frames()) return 0;
            return (samples[frame * 2] + samples[frame * 2 + 1]) * 0.5 * gain(volume);
        }

        public void pcm(byte[] target, int frame, int count, double volume) {
            double gain = gain(volume);
            for (int i = 0; i < count * 2; i++) {
                int sample = (int) Math.round(samples[frame * 2 + i] * gain * 32767);
                target[i * 2] = (byte) sample;
                target[i * 2 + 1] = (byte) (sample >> 8);
            }
        }
    }

    private SoundSynthesizer() {}

    static double gain(double volume) {
        return Double.isFinite(volume) ? Math.clamp(volume, 0, 1) : 0;
    }

    public static Sound render(Cue cue, Settings settings) {
        double duration = cue == Cue.THINK ? 1.35 : 0.85;
        int dryFrames = (int) (duration * SAMPLE_RATE);
        int tail = settings.echo() || settings.reverb() ? SAMPLE_RATE : 0;
        float[] dry = new float[dryFrames + tail];
        double phase = 0;
        for (int i = 0; i < dryFrames; i++) {
            double t = i / (double) SAMPLE_RATE;
            double progress = t / duration;
            double frequency = cue.start + (cue.end - cue.start) * progress;
            phase += 2 * Math.PI * frequency / SAMPLE_RATE;
            double envelope = Math.pow(Math.sin(Math.PI * progress), 2);
            double pulse = cue == Cue.THINK ? 0.55 + 0.45 * Math.cos(t * Math.PI * 8) : 1;
            dry[i] = (float) (0.34 * envelope * pulse
                    * (Math.sin(phase) + 0.22 * Math.sin(phase * 2) + 0.08 * Math.sin(phase * 3)));
        }
        float[] output = new float[dry.length * 2];
        double previousLeft = 0;
        double previousRight = 0;
        for (int i = 0; i < dry.length; i++) {
            double left = dry[i];
            double right = dry[i];
            if (settings.echo()) {
                for (int tap = 1; tap <= 3; tap++) {
                    double amplitude = Math.pow(0.38, tap);
                    left += delayed(dry, i, 0.18 * tap) * amplitude;
                    right += delayed(dry, i, (settings.stereo() ? 0.213 : 0.18) * tap) * amplitude;
                }
            }
            if (settings.reverb()) {
                for (int tap = 1; tap <= 12; tap++) {
                    double amplitude = 0.12 * Math.exp(-tap * 0.23);
                    left += delayed(dry, i, 0.029 * tap + 0.007 * (tap % 3)) * amplitude;
                    right += delayed(dry, i, (settings.stereo() ? 0.037 : 0.029) * tap
                            + 0.007 * (tap % 3)) * amplitude;
                }
            }
            if (settings.stereo()) {
                double pan = Math.sin(i / (double) SAMPLE_RATE * 3) * 0.22;
                left *= 1 - pan;
                right *= 1 + pan;
            }
            if (settings.soft()) {
                left = previousLeft + 0.075 * (left - previousLeft);
                right = previousRight + 0.075 * (right - previousRight);
                previousLeft = left;
                previousRight = right;
            }
            // Fixed headroom, including echoes and stereo pan; no hard clipping.
            output[i * 2] = (float) (Math.tanh(left) * 0.8);
            output[i * 2 + 1] = (float) (Math.tanh(right) * 0.8);
        }
        return new Sound(output);
    }

    private static float delayed(float[] samples, int frame, double seconds) {
        int index = frame - (int) (seconds * SAMPLE_RATE);
        return index < 0 ? 0 : samples[index];
    }
}
