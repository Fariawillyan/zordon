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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A moldura do protocolo com o sidecar: tamanho do cabeçalho (u32 big-endian),
 * cabeçalho JSON e, se ele tiver {@code bytes}, a carga. A mesma de
 * {@code voice/zordon_voice/framing.py}.
 */
final class EngineFrames {

    static final int MAX_HEADER = 64 * 1024;
    static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    private static final ObjectMapper json = new ObjectMapper();

    private EngineFrames() {}

    record Message(Map<?, ?> header, byte[] payload) {}


    static ByteBuffer encode(Map<String, Object> header, byte[] payload) throws IOException {
        Map<String, Object> full = new LinkedHashMap<>(header);
        if (payload != null && payload.length > 0) {
            full.put("bytes", payload.length);
        }
        byte[] raw = json.writeValueAsBytes(full);
        int size = payload == null ? 0 : payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(4 + raw.length + size);
        buffer.putInt(raw.length).put(raw);
        if (size > 0) {
            buffer.put(payload);
        }
        return buffer.flip();
    }

    static Message read(ReadableByteChannel channel) throws IOException {
        int size = readFully(channel, 4).getInt();
        if (size <= 0 || size > MAX_HEADER) {
            throw new IOException("cabeçalho de " + size + " bytes do motor");
        }
        Map<?, ?> header = json.readValue(new String(readFully(channel, size).array(), StandardCharsets.UTF_8),
                Map.class);
        int bytes = header.get("bytes") instanceof Number number ? number.intValue() : 0;
        if (bytes < 0 || bytes > MAX_PAYLOAD) {
            throw new IOException("carga de " + bytes + " bytes do motor");
        }
        return new Message(header, bytes == 0 ? new byte[0] : readFully(channel, bytes).array());
    }

    private static ByteBuffer readFully(ReadableByteChannel channel, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("o motor fechou a conexão");
            }
        }
        return buffer.flip();
    }
}
