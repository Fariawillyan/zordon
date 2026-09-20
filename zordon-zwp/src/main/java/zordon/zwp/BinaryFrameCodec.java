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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;

/**
 * Cabeçalho de 8 bytes, big-endian: {@code 0x5A}, tipo, streamId u16, seq u32
 * (docs/api/zwp-protocol.md §7). A magia existe para falhar alto quando um frame
 * binário chega onde não devia.
 */
@Spec("SPEC-009")
public final class BinaryFrameCodec {

    public static final int HEADER_BYTES = 8;
    public static final byte MAGIC = 0x5A;

    private BinaryFrameCodec() {}

    public static ByteBuffer encode(BinaryFrame frame) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + frame.payload().length).order(ByteOrder.BIG_ENDIAN);
        buffer.put(MAGIC);
        buffer.put((byte) frame.type().code());
        buffer.putShort((short) frame.streamId());
        buffer.putInt((int) frame.seq());
        buffer.put(frame.payload());
        return buffer.flip();
    }

    /** @throws ZwpCodecException se a magia, o tamanho ou o tipo não baterem. */
    public static BinaryFrame decode(ByteBuffer message) {
        ByteBuffer buffer = message.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < HEADER_BYTES) {
            throw new ZwpCodecException("frame binário com " + buffer.remaining() + " bytes; o cabeçalho tem 8");
        }
        byte magic = buffer.get();
        if (magic != MAGIC) {
            throw new ZwpCodecException("frame binário sem a magia 0x5A (veio 0x" + Integer.toHexString(magic & 0xFF) + ")");
        }
        FrameType type;
        try {
            type = FrameType.of(buffer.get() & 0xFF);
        } catch (IllegalArgumentException e) {
            throw new ZwpCodecException(e.getMessage());
        }
        int streamId = buffer.getShort() & 0xFFFF;
        long seq = buffer.getInt() & 0xFFFF_FFFFL;
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new BinaryFrame(type, streamId, seq, payload);
    }
}
