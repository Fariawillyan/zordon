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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.desktop.audio.SoundSynthesizer.Cue;
import zordon.desktop.audio.SoundSynthesizer.Settings;

class SoundPlayerTest {
    @AcceptanceCriteria("SPEC-008/CA-5")
    @Test
    void stopAndReplacementCloseTheDeviceWithoutQueueingSounds() throws Exception {
        RecordingOutput first = new RecordingOutput();
        RecordingOutput second = new RecordingOutput();
        AtomicInteger opened = new AtomicInteger();
        try (SoundPlayer player = new SoundPlayer(() -> opened.getAndIncrement() == 0 ? first : second)) {
            player.play(Cue.ACTIVATE, Settings.DEFAULT);
            assertThat(first.written.await(2, TimeUnit.SECONDS)).isTrue();
            player.play(Cue.COMPLETE, Settings.DEFAULT);
            assertThat(second.written.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(first.closed.getCount()).isZero();
            assertThat(player.playing()).isTrue();
            player.stop();
            assertThat(second.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(player.playing()).isFalse();
            assertThat(player.level()).isZero();
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-5")
    @Test
    void lateOpenAfterCancellationClosesAndNeverStarts() throws Exception {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingOutput output = new RecordingOutput();
        try (SoundPlayer player = new SoundPlayer(() -> {
            opening.countDown();
            release.await(2, TimeUnit.SECONDS);
            return output;
        })) {
            player.play(Cue.ACTIVATE, Settings.DEFAULT);
            assertThat(opening.await(2, TimeUnit.SECONDS)).isTrue();
            player.stop();
            release.countDown();
            assertThat(output.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(output.started).isFalse();
            assertThat(player.error()).isEmpty();
        } finally { release.countDown(); }
    }

    @AcceptanceCriteria("SPEC-008/CA-5")
    @Test
    void unavailableDeviceIsReportedAndRetryCanSucceed() throws Exception {
        AtomicInteger attempt = new AtomicInteger();
        RecordingOutput output = new RecordingOutput();
        try (SoundPlayer player = new SoundPlayer(() -> {
            if (attempt.getAndIncrement() == 0) throw new IllegalStateException("no device");
            return output;
        })) {
            player.play(Cue.ALERT, Settings.DEFAULT);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (player.playing() && System.nanoTime() < deadline) Thread.sleep(5);
            // No WSL a mensagem manda abrir pelo Windows (SPEC-008 v2); fora dele, é a genérica.
            assertThat(player.error()).isIn(SoundPlayer.OUTPUT_UNAVAILABLE, SoundPlayer.NO_OUTPUT_IN_WSL);
            player.play(Cue.ALERT, Settings.DEFAULT);
            assertThat(output.written.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(player.error()).isEmpty();
        }
    }

    @AcceptanceCriteria("SPEC-008/CA-4")
    @Test
    void meterFollowsDeviceCursorAndVolumeInsteadOfWallClock() throws Exception {
        RecordingOutput output = new RecordingOutput();
        try (SoundPlayer player = new SoundPlayer(() -> output)) {
            player.play(Cue.RESPOND, Settings.DEFAULT);
            assertThat(output.written.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(player.level()).isZero();
            output.frame = 20000;
            assertThat(player.level()).isPositive();
            player.setVolume(0);
            assertThat(player.level()).isZero();
            assertThat(player.sample(-100)).isZero();
        }
    }

    private static final class RecordingOutput implements SoundPlayer.Output {
        final CountDownLatch written = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean started;
        volatile int frame;
        @Override public void start() { started = true; }
        @Override public int write(byte[] data, int offset, int length) { written.countDown(); return length; }
        @Override public long framePosition() { return frame; }
        @Override public void drain() {
            try { closed.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
        @Override public void close() { closed.countDown(); }
    }
}
