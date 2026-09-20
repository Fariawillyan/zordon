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
 * RMS e pico de um frame PCM 16 bits little-endian, em escala linear de 0 a 1.
 * Só dois números saem daqui; o áudio não.
 */
public record AudioLevel(double rms, double peak) {

    /** Piso de dBFS: abaixo disto é silêncio digital. */
    public static final double FLOOR_DBFS = -90;

    public static AudioLevel of(byte[] pcm) {
        int samples = pcm.length / 2;
        if (samples == 0) {
            return new AudioLevel(0, 0);
        }
        double sumSquares = 0;
        int peak = 0;
        for (int i = 0; i < samples; i++) {
            int sample = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
            sumSquares += (double) sample * sample;
            peak = Math.max(peak, Math.abs(sample));
        }
        return new AudioLevel(Math.sqrt(sumSquares / samples) / 32768.0, peak / 32768.0);
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
