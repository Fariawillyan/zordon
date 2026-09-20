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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;

class StreamingSinkTest {

    private final List<BinaryFrame> sent = new CopyOnWriteArrayList<>();
    private final StreamingSink sink = new StreamingSink(frame -> sent.add(frame));
    private final byte[] frame = new byte[Microphone.FRAME_BYTES];

    @AcceptanceCriteria("SPEC-009/CA-3")
    @Test
    void enviaAudioInNoStreamAnunciadoComSeqCrescenteEAudioEndNoFim() {
        FakeSound sound = new FakeSound();
        Microphone microphone = new Microphone(sound, sink);
        AudioMethods audio = new AudioMethods(microphone, sink);

        audio.setCaptureEnabled(Map.of("enabled", true, "streamId", 7));
        sink.accept(frame, frame.length);
        sink.accept(frame, frame.length);
        audio.setCaptureEnabled(Map.of("enabled", false));

        assertThat(sent).extracting(BinaryFrame::type)
                .containsExactly(FrameType.AUDIO_IN, FrameType.AUDIO_IN, FrameType.AUDIO_END);
        assertThat(sent).extracting(BinaryFrame::streamId).containsOnly(7);
        assertThat(sent).extracting(BinaryFrame::seq).containsExactly(0L, 1L, 2L);
        assertThat(sent.getFirst().payload()).hasSize(Microphone.FRAME_BYTES);
    }

    @AcceptanceCriteria("SPEC-009/CA-4")
    @Test
    void nuncaPassaDoCreditoEDescartaEmVezDeGuardar() {
        sink.creditLimit(50);
        sink.begin(3);

        for (int i = 0; i < 80; i++) {
            sink.accept(frame, frame.length);
        }

        assertThat(sent).hasSize(50);
        assertThat(sink.dropped()).isEqualTo(30);
        assertThat(sink.available()).isZero();
    }

    @AcceptanceCriteria("SPEC-009/CA-4")
    @Test
    void comCreditoDevolvidoVoltaAEnviarSemPassarDoLimite() {
        sink.creditLimit(50);
        sink.begin(3);
        for (int i = 0; i < 60; i++) {
            sink.accept(frame, frame.length);
        }

        new AudioMethods(new Microphone(new FakeSound(), sink), sink).credit(Map.of("streamId", 3, "frames", 10));
        sink.grant(99, 40);
        for (int i = 0; i < 20; i++) {
            sink.accept(frame, frame.length);
        }

        assertThat(sent).hasSize(60);
        assertThat(sink.available()).isZero();
        sink.grant(3, 500);
        assertThat(sink.available()).isEqualTo(50);
    }

    @AcceptanceCriteria("SPEC-009/CA-4")
    @Test
    void perderONucleoEncerraOStreamSemAviso() {
        Microphone microphone = new Microphone(new FakeSound(), sink);
        ZordonHost.Session session = new ZordonHost.Session(microphone, sink);
        sink.begin(5);
        microphone.enable();

        session.onOffline("conexão encerrada");
        sink.accept(frame, frame.length);

        assertThat(sent).isEmpty();
        assertThat(microphone.capturing()).isFalse();
    }
}
