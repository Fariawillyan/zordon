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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;

/** O host tocando a fala do Zordon (SPEC-011 CA-6). */
class SpeakerTest {

    private final FakeSound sound = new FakeSound();
    private final Speaker speaker = new Speaker(sound);
    private final AudioMethods audio = new AudioMethods(new Microphone(sound, new DiscardingSink())).speaker(speaker);

    @AcceptanceCriteria("SPEC-011/CA-6")
    @Test
    void playAbreNaTaxaPedidaTocaEmOrdemEOFimDrenaEFecha() throws Exception {
        assertThat(audio.play(Map.of("streamId", 5, "format", Map.of("rate", 22_050, "channels", 1))))
                .isEqualTo(Map.of("accepted", true));
        FakeSound.Playback line = sound.playbacks.getLast();
        assertThat(line.rate).isEqualTo(22_050);

        speaker.frame(new BinaryFrame(FrameType.AUDIO_OUT, 5, 0, new byte[] {1, 2}));
        speaker.frame(new BinaryFrame(FrameType.AUDIO_OUT, 9, 0, new byte[] {9, 9}));
        speaker.frame(new BinaryFrame(FrameType.AUDIO_OUT, 5, 1, new byte[] {3, 4}));
        speaker.frame(BinaryFrame.end(5, 2));

        await(() -> line.closed);
        assertThat(line.written.toByteArray()).containsExactly(1, 2, 3, 4);
        assertThat(line.drained).isTrue();
        assertThat(speaker.playing()).isFalse();
    }

    @AcceptanceCriteria("SPEC-011/CA-6")
    @Test
    void stopCortaNaHoraEFramesDepoisSaoIgnorados() throws Exception {
        audio.play(Map.of("streamId", 7, "format", Map.of("rate", 22_050)));
        FakeSound.Playback line = sound.playbacks.getLast();

        audio.stopPlayback(Map.of("streamId", 7));
        speaker.frame(new BinaryFrame(FrameType.AUDIO_OUT, 7, 0, new byte[] {1}));

        await(() -> line.closed);
        assertThat(line.drained).isFalse();
        assertThat(line.written.size()).isZero();
    }

    @AcceptanceCriteria("SPEC-011/CA-6")
    @Test
    void saidaQueNaoAbreRecusaOPlay() {
        sound.playbackFails = "sem saída de áudio";

        assertThat(audio.play(Map.of("streamId", 1, "format", Map.of("rate", 22_050))))
                .isEqualTo(Map.of("accepted", false));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condição não atingida em 5 s");
            }
            Thread.sleep(10);
        }
    }
}
