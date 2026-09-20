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
package zordon.desktop.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static zordon.desktop.ui.FxTestSupport.onFx;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.audio.SoundPlayer;
import zordon.desktop.audio.SoundSynthesizer;

/** O painel diz onde o som vai tocar e, depois, se tocou (SPEC-008 v2). */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class VoiceEffectsStatusTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.start();
    }

    @AcceptanceCriteria("SPEC-008/CA-9")
    @Test
    void nomeiaASaidaAntesEConfirmaDepoisDeTocar() throws Exception {
        AtomicLong clock = new AtomicLong();
        try (SoundPlayer player = new SoundPlayer(() -> new SoundPlayer.Output() {
            private long written;
            private long position;

            @Override public String name() { return "Alto-falantes (Realtek)"; }
            @Override public void start() {}
            @Override public int write(byte[] data, int offset, int length) { written += length / 4; return length; }
            @Override public long framePosition() { return position; }
            @Override public void drain() {
                position = written;
                clock.addAndGet((long) (written * 1e9 / SoundSynthesizer.SAMPLE_RATE));
            }
            @Override public void close() {}
        }, clock::get)) {
            VoiceEffectsPane pane = onFx(() -> new VoiceEffectsPane(player, "Alto-falantes (Realtek)"));
            assertThat(onFx(pane::statusText)).isEqualTo("●  PRONTO PARA TESTAR · SAÍDA: Alto-falantes (Realtek)");

            player.play(SoundSynthesizer.Cue.ACTIVATE, SoundSynthesizer.Settings.DEFAULT);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (player.playing() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            assertThat(onFx(pane::statusText)).startsWith("✓  Tocou em Alto-falantes (Realtek) · ").endsWith(" s");
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-9")
    @Test
    void semSaidaDizQueNaoEncontrouNenhuma() throws Exception {
        try (SoundPlayer player = new SoundPlayer(() -> { throw new IllegalStateException("sem saída"); })) {
            VoiceEffectsPane pane = onFx(() -> new VoiceEffectsPane(player, ""));

            assertThat(onFx(pane::statusText)).contains("nenhuma saída encontrada");
        }
    }
}
