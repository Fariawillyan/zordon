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
package zordon.api.zwp;

import java.util.Objects;

/**
 * Um frame binário do ZWP (docs/api/zwp-protocol.md §7). O stream é sempre
 * anunciado antes por um método JSON; o frame só carrega o id dele.
 *
 * @param streamId 1 a 65535 (u16)
 * @param seq 0 a 2³²−1 (u32)
 */
public record BinaryFrame(FrameType type, int streamId, long seq, byte[] payload) {

    public static final int MAX_STREAM_ID = 0xFFFF;
    public static final long MAX_SEQ = 0xFFFF_FFFFL;

    public BinaryFrame {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (streamId < 0 || streamId > MAX_STREAM_ID) {
            throw new IllegalArgumentException("streamId fora de u16: " + streamId);
        }
        if (seq < 0 || seq > MAX_SEQ) {
            throw new IllegalArgumentException("seq fora de u32: " + seq);
        }
    }

    public static BinaryFrame end(int streamId, long seq) {
        return new BinaryFrame(FrameType.AUDIO_END, streamId, seq, new byte[0]);
    }

    /** Compara o conteúdo do payload, e não a identidade do array. */
    @Override
    public boolean equals(Object other) {
        return other instanceof BinaryFrame frame
                && type == frame.type
                && streamId == frame.streamId
                && seq == frame.seq
                && java.util.Arrays.equals(payload, frame.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, streamId, seq, java.util.Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return "BinaryFrame[" + type + ", stream " + streamId + ", seq " + seq + ", " + payload.length + " bytes]";
    }
}
