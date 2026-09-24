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

/**
 * RMS, pico e energia por banda de um frame PCM 16 bits little-endian, em
 * escala linear de 0 a 1. Só métricas saem daqui; o áudio não.
 */
public record AudioLevel(double rms, double peak, double bass, double mid, double treble) {

    /** Piso de dBFS: abaixo disto é silêncio digital. */
    public static final double FLOOR_DBFS = -90;
    private static final double SAMPLE_RATE = 16_000;

    public static AudioLevel of(byte[] pcm) {
        int samples = pcm.length / 2;
        if (samples == 0) {
            return new AudioLevel(0, 0, 0, 0, 0);
        }
        double sumSquares = 0;
        int peak = 0;
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            int sample = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
            double value = sample / 32768.0;
            values[i] = value;
            sumSquares += value * value;
            peak = Math.max(peak, Math.abs(sample));
        }
        return new AudioLevel(Math.sqrt(sumSquares / samples), peak / 32768.0,
                bandRms(values, 60, 250), bandRms(values, 250, 2_000), bandRms(values, 2_000, 7_000));
    }

    /** Energia espectral simples, suficiente para o núcleo visual em tempo real. */
    private static double bandRms(double[] values, double lowHz, double highHz) {
        int size = values.length;
        int first = Math.max(1, (int) Math.ceil(lowHz * size / SAMPLE_RATE));
        int last = Math.min(size / 2 - 1, (int) Math.floor(highHz * size / SAMPLE_RATE));
        double energy = 0;
        for (int bin = first; bin <= last; bin++) {
            double real = 0;
            double imaginary = 0;
            for (int sample = 0; sample < size; sample++) {
                double window = 0.5 - 0.5 * Math.cos(2 * Math.PI * sample / (size - 1));
                double angle = 2 * Math.PI * bin * sample / size;
                real += values[sample] * window * Math.cos(angle);
                imaginary -= values[sample] * window * Math.sin(angle);
            }
            energy += real * real + imaginary * imaginary;
        }
        return Math.min(1, Math.sqrt(2 * energy) / size * 2.67);
    }

    public double rmsDbfs() {
        return dbfs(rms);
    }

    public double peakDbfs() {
        return dbfs(peak);
    }

    /** Uma casa decimal; de {@value #FLOOR_DBFS} a 0. */
    public static double dbfs(double linear) {
        if (linear <= 0) {
            return FLOOR_DBFS;
        }
        double value = Math.max(FLOOR_DBFS, Math.min(0, 20 * Math.log10(linear)));
        return Math.round(value * 10) / 10.0;
    }
}
