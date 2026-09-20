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
package zordon.zwp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;

class BinaryFrameCodecTest {

    @AcceptanceCriteria("SPEC-009/CA-1")
    @Test
    void cabecalhoDeOitoBytesBigEndianComMagia() {
        BinaryFrame frame = new BinaryFrame(FrameType.AUDIO_IN, 0x1234, 0x0A0B0C0DL, new byte[] {1, 2, 3});

        ByteBuffer bytes = BinaryFrameCodec.encode(frame);

        byte[] wire = new byte[bytes.remaining()];
        bytes.duplicate().get(wire);
        assertThat(wire).containsExactly(0x5A, 0x01, 0x12, 0x34, 0x0A, 0x0B, 0x0C, 0x0D, 1, 2, 3);
        assertThat(BinaryFrameCodec.decode(bytes)).isEqualTo(frame);
    }

    @AcceptanceCriteria("SPEC-009/CA-1")
    @Test
    void limitesDeU16EU32IdaEVolta() {
        BinaryFrame frame = new BinaryFrame(FrameType.AUDIO_END, BinaryFrame.MAX_STREAM_ID, BinaryFrame.MAX_SEQ, new byte[0]);

        assertThat(BinaryFrameCodec.decode(BinaryFrameCodec.encode(frame))).isEqualTo(frame);
        assertThatThrownBy(() -> new BinaryFrame(FrameType.AUDIO_IN, 0x1_0000, 0, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BinaryFrame(FrameType.AUDIO_IN, 1, BinaryFrame.MAX_SEQ + 1, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @AcceptanceCriteria("SPEC-009/CA-1")
    @Test
    void magiaErradaCabecalhoCurtoETipoDesconhecidoSaoRecusados() {
        assertThatThrownBy(() -> BinaryFrameCodec.decode(ByteBuffer.wrap(new byte[] {0x41, 1, 0, 1, 0, 0, 0, 0})))
                .isInstanceOf(ZwpCodecException.class).hasMessageContaining("magia");
        assertThatThrownBy(() -> BinaryFrameCodec.decode(ByteBuffer.wrap(new byte[] {0x5A, 1, 0})))
                .isInstanceOf(ZwpCodecException.class).hasMessageContaining("cabeçalho");
        assertThatThrownBy(() -> BinaryFrameCodec.decode(ByteBuffer.wrap(new byte[] {0x5A, 0x7F, 0, 1, 0, 0, 0, 0})))
                .isInstanceOf(ZwpCodecException.class).hasMessageContaining("desconhecido");
    }
}
