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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;

class AudioIngestTest {

    private final AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private final List<Map<String, Object>> credits = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> levels = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> alerts = new CopyOnWriteArrayList<>();
    private final AudioIngest ingest = new AudioIngest(
            (session, method, params) -> credits.add(Map.of("session", session, "method", method, "params", params)),
            levels::add, alerts::add, nanos::get);

    @AcceptanceCriteria("SPEC-009/CA-6")
    @Test
    void nivelDeSilencioEDeUmSenoA20Dbfs() {
        assertThat(AudioLevel.of(pcm(0)).rmsDbfs()).isEqualTo(AudioLevel.FLOOR_DBFS);
        AudioLevel sine = AudioLevel.of(sine(0.1));
        // Seno de amplitude 0,1: pico -20 dBFS, RMS 3 dB abaixo.
        assertThat(sine.peakDbfs()).isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.2));
        assertThat(sine.rmsDbfs()).isCloseTo(-23.0, org.assertj.core.data.Offset.offset(0.2));
        assertThat(sine.mid()).isGreaterThan(sine.bass() * 5);
        assertThat(sine.mid()).isGreaterThan(sine.treble() * 5);
    }

    @AcceptanceCriteria("SPEC-009/CA-6")
    @Test
    void publicaNoMaximo20NiveisPorSegundoESoQuandoPedido() {
        ingest.open("host", 1);
        for (int i = 0; i < 50; i++) {
            advance(20);
            ingest.accept("host", audio(1, i, sine(0.1)));
        }
        assertThat(levels).isEmpty();

        ingest.levels(true);
        for (int i = 50; i < 100; i++) {
            advance(20);
            ingest.accept("host", audio(1, i, sine(0.1)));
        }

        // 50 frames de 20 ms = 1 s: no máximo 20 níveis.
        assertThat(levels).hasSizeBetween(17, 20);
        assertThat(levels.getFirst()).containsOnlyKeys("rms", "peak", "bass", "mid", "treble");
    }

    @AcceptanceCriteria("SPEC-009/CA-6")
    @Test
    void devolveCreditoACadaDezFramesConsumidos() {
        ingest.open("host", 4);

        for (int i = 0; i < 25; i++) {
            ingest.accept("host", audio(4, i, pcm(0)));
        }

        assertThat(credits).hasSize(2).allSatisfy(credit -> {
            assertThat(credit).containsEntry("session", "host").containsEntry("method", "audio.credit");
            assertThat(credit.get("params")).isEqualTo(Map.of("streamId", 4, "frames", 10));
        });
    }

    @AcceptanceCriteria("SPEC-009/CA-5")
    @Test
    void streamNaoAnunciadoOuOutraSessaoSaoRecusadosComUmAlertaPorMinuto() {
        ingest.open("host", 1);

        ingest.accept("host", audio(9, 0, pcm(0)));
        ingest.accept("host", audio(9, 1, pcm(0)));
        ingest.accept("intruso", audio(1, 0, pcm(0)));
        advance(61_000);
        ingest.accept("host", audio(9, 2, pcm(0)));

        assertThat(alerts).hasSize(3);
        assertThat(alerts.getFirst()).containsEntry("sessionId", "host").containsEntry("streamId", 9);
        assertThat(alerts.get(1)).containsEntry("sessionId", "intruso");
        assertThat(ingest.stats().frames()).isZero();
    }

    @AcceptanceCriteria("SPEC-009/CA-5")
    @Test
    void framesAtrasadosDeStreamEncerradoCaemEmSilencio() {
        ingest.open("host", 1);
        ingest.close();

        ingest.accept("host", audio(1, 0, pcm(0)));
        ingest.accept("host", BinaryFrame.end(1, 1));

        assertThat(alerts).isEmpty();
    }

    @Test
    void estatisticasDoTesteUsamOsFramesComSinal() {
        ingest.open("host", 1);
        for (int i = 0; i < 10; i++) {
            ingest.accept("host", audio(1, i, i < 5 ? pcm(0) : sine(0.1)));
        }

        AudioIngest.Stats stats = ingest.stats();

        assertThat(stats.frames()).isEqualTo(10);
        assertThat(stats.peakDbfs()).isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.2));
        assertThat(stats.averageDbfs()).isCloseTo(-23.0, org.assertj.core.data.Offset.offset(0.2));
    }

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    static BinaryFrame audio(int stream, long seq, byte[] pcm) {
        return new BinaryFrame(FrameType.AUDIO_IN, stream, seq, pcm);
    }

    static byte[] pcm(int value) {
        byte[] frame = new byte[640];
        for (int i = 0; i < 320; i++) {
            frame[2 * i] = (byte) value;
            frame[2 * i + 1] = (byte) (value >> 8);
        }
        return frame;
    }

    /** 20 ms de um seno de 1 kHz com a amplitude pedida (1 = fundo de escala). */
    static byte[] sine(double amplitude) {
        byte[] frame = new byte[640];
        for (int i = 0; i < 320; i++) {
            int sample = (int) Math.round(amplitude * 32767 * Math.sin(2 * Math.PI * 1000 * i / 16_000.0));
            frame[2 * i] = (byte) sample;
            frame[2 * i + 1] = (byte) (sample >> 8);
        }
        return frame;
    }
}
