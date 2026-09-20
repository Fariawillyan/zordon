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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.audio.SoundSynthesizer.Cue;
import zordon.desktop.audio.SoundSynthesizer.Settings;
import zordon.desktop.audio.SoundSynthesizer.Sound;

class SoundSynthesizerTest {
    private static final Settings DRY = new Settings(false, false, false, false);

    @AcceptanceCriteria("SPEC-008/CA-2")
    @ParameterizedTest
    @EnumSource(Cue.class)
    void cuesAreFiniteBoundedAndFadeAtBothEnds(Cue cue) {
        Sound sound = SoundSynthesizer.render(cue, Settings.DEFAULT);
        assertThat(SoundSynthesizer.SAMPLE_RATE).isEqualTo(44100);
        assertThat(sound.samples().length).isEqualTo(sound.frames() * 2);
        double peak = 0;
        for (float value : sound.samples()) {
            assertThat(Float.isFinite(value)).isTrue();
            peak = Math.max(peak, Math.abs(value));
        }
        assertThat(peak).isBetween(0.1, 0.8);
        assertThat(sound.seconds()).isBetween(1.8, 2.4);
        assertThat(sound.sample(0, 1)).isZero();
        assertThat(sound.sample(sound.frames() - 1, 1)).isLessThan(0.0001);
        Sound dry = SoundSynthesizer.render(cue, DRY);
        assertThat(Math.abs(dry.sample(dry.frames() - 1, 1))).isLessThan(0.0001);
    }

    @AcceptanceCriteria("SPEC-008/CA-3")
    @Test
    void effectsChangeSignalAndProduceRealTailsAndStereo() {
        Sound dry = SoundSynthesizer.render(Cue.ACTIVATE, DRY);
        for (Settings settings : new Settings[]{new Settings(true, false, false, false),
                new Settings(false, true, false, false)}) {
            Sound processed = SoundSynthesizer.render(Cue.ACTIVATE, settings);
            assertThat(processed.frames()).isGreaterThan(dry.frames());
            assertThat(processed.rms(dry.frames() + 2000, 1)).isPositive();
        }
        Sound soft = SoundSynthesizer.render(Cue.ACTIVATE, new Settings(false, false, true, false));
        assertThat(soft.rms(24000, 1)).isLessThan(dry.rms(24000, 1));
        Sound stereo = SoundSynthesizer.render(Cue.ACTIVATE, new Settings(false, false, false, true));
        assertThat(dry.samples()[40000]).isEqualTo(dry.samples()[40001]);
        assertThat(stereo.samples()[40000]).isNotEqualTo(stereo.samples()[40001]);
    }

    @AcceptanceCriteria("SPEC-008/CA-4")
    @Test
    void pcmVolumeAndMeterUseTheSameGainAndFrameCursor() {
        Sound sound = SoundSynthesizer.render(Cue.RESPOND, DRY);
        byte[] muted = new byte[4096];
        sound.pcm(muted, 20000, 1024, 0);
        assertThat(muted).containsOnly((byte) 0);
        assertThat(sound.rms(20000, 0)).isZero();
        assertThat(sound.rms(0, 1)).isZero();
        assertThat(sound.rms(20000, 0.5)).isEqualTo(sound.rms(20000, 1) * 0.5);
        assertThat(sound.sample(20000, 0)).isZero();
        byte[] full = new byte[4096];
        byte[] half = new byte[4096];
        sound.pcm(full, 20000, 1024, 1);
        sound.pcm(half, 20000, 1024, 0.5);
        for (int i = 0; i < full.length; i += 2) {
            int a = (short) ((full[i] & 255) | (full[i + 1] << 8));
            int b = (short) ((half[i] & 255) | (half[i + 1] << 8));
            assertThat(Math.abs(a * 0.5 - b)).isLessThanOrEqualTo(1);
        }
        assertThat(SoundSynthesizer.gain(Double.NaN)).isZero();
        assertThat(SoundSynthesizer.gain(2)).isEqualTo(1);
    }
}
