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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.audio.SoundSynthesizer.Cue;
import zordon.desktop.audio.SoundSynthesizer.Settings;

/** "Tocou" só com prova: posição da saída no fim, em tempo real (SPEC-008 v2). */
class PlaybackProofTest {

    /** Relógio que só anda quando a saída "toca". */
    private final AtomicLong clock = new AtomicLong();

    @AcceptanceCriteria("SPEC-008/CA-8")
    @Test
    void saidaQueTocaEmTempoRealEComprovada() throws Exception {
        try (SoundPlayer player = new SoundPlayer(() -> new FakeOutput(Behavior.REAL_TIME), clock::get)) {
            Settings settings = Settings.DEFAULT;

            SoundPlayer.Outcome outcome = playAndWait(player, Cue.ACTIVATE, settings);

            assertThat(outcome).isInstanceOf(SoundPlayer.Played.class);
            SoundPlayer.Played played = (SoundPlayer.Played) outcome;
            assertThat(played.output()).isEqualTo("Alto-falantes de teste");
            assertThat(played.frames()).isEqualTo(SoundSynthesizer.render(Cue.ACTIVATE, settings).frames());
            assertThat(player.error()).isEmpty();
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-8")
    @Test
    void posicaoParadaEFalhaMesmoComAEscritaAceita() throws Exception {
        try (SoundPlayer player = new SoundPlayer(() -> new FakeOutput(Behavior.FROZEN), clock::get)) {
            SoundPlayer.Outcome outcome = playAndWait(player, Cue.ALERT, Settings.DEFAULT);

            assertThat(outcome).isEqualTo(new SoundPlayer.Failed(SoundPlayer.NOT_REPRODUCED));
            assertThat(player.error()).isEqualTo(SoundPlayer.NOT_REPRODUCED);
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-8")
    @Test
    void saidaQueEngoleOAudioNaHoraEFalha() throws Exception {
        try (SoundPlayer player = new SoundPlayer(() -> new FakeOutput(Behavior.INSTANT), clock::get)) {
            SoundPlayer.Outcome outcome = playAndWait(player, Cue.COMPLETE, Settings.DEFAULT);

            assertThat(outcome).isEqualTo(new SoundPlayer.Failed(SoundPlayer.NOT_REAL_TIME));
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-9")
    @Test
    void semSaidaNoWslAMensagemMandaAbrirPeloWindows() {
        assertThat(SoundPlayer.NO_OUTPUT_IN_WSL).contains("WSL").contains("Windows");
        assertThat(SoundPlayer.NO_OUTPUT_IN_WSL).isNotEqualTo(SoundPlayer.OUTPUT_UNAVAILABLE);
        assertThat(SoundPlayer.OUTPUT_UNAVAILABLE).doesNotContain("WSL");
    }

    @AcceptanceCriteria("SPEC-008/CA-11")
    @Test
    void aVerificacaoPassaSoComOsSeisSinaisComprovados() throws Exception {
        ByteArrayOutputStream report = new ByteArrayOutputStream();
        int failures;
        try (SoundPlayer player = new SoundPlayer(() -> new FakeOutput(Behavior.REAL_TIME), clock::get)) {
            failures = PlaybackCheck.run(player, "Alto-falantes de teste", new PrintStream(report, true, StandardCharsets.UTF_8));
        }

        assertThat(failures).isZero();
        assertThat(report.toString(StandardCharsets.UTF_8))
                .contains("saída padrão: Alto-falantes de teste")
                .contains("seis sinais comprovados");
    }

    @AcceptanceCriteria("SPEC-008/CA-11")
    @Test
    void aVerificacaoReprovaQuandoASaidaNaoToca() throws Exception {
        ByteArrayOutputStream report = new ByteArrayOutputStream();
        int failures;
        try (SoundPlayer player = new SoundPlayer(() -> new FakeOutput(Behavior.FROZEN), clock::get)) {
            failures = PlaybackCheck.run(player, "", new PrintStream(report, true, StandardCharsets.UTF_8));
        }

        assertThat(failures).isEqualTo(Cue.values().length);
        assertThat(report.toString(StandardCharsets.UTF_8)).contains("saída padrão: nenhuma").contains("FALHOU");
    }

    private static SoundPlayer.Outcome playAndWait(SoundPlayer player, Cue cue, Settings settings)
            throws InterruptedException {
        player.play(cue, settings);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (player.playing() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return player.lastOutcome();
    }

    private enum Behavior {
        REAL_TIME,
        FROZEN,
        INSTANT
    }

    /** Conta o que foi escrito; no drain, "toca" conforme o comportamento. */
    private final class FakeOutput implements SoundPlayer.Output {

        private final Behavior behavior;
        private long written;
        private long position;

        FakeOutput(Behavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public String name() {
            return "Alto-falantes de teste";
        }

        @Override
        public void start() {}

        @Override
        public int write(byte[] data, int offset, int length) {
            written += length / 4;
            return length;
        }

        @Override
        public long framePosition() {
            return position;
        }

        @Override
        public void drain() {
            if (behavior != Behavior.FROZEN) {
                position = written;
            }
            if (behavior == Behavior.REAL_TIME) {
                clock.addAndGet((long) (written * 1e9 / SoundSynthesizer.SAMPLE_RATE));
            }
        }

        @Override
        public void close() {}
    }
}
