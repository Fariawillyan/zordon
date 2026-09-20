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

/** Tipos de frame binário (docs/api/zwp-protocol.md §7). */
public enum FrameType {
    /** Host → núcleo: PCM 16 kHz, mono, s16le, 20 ms. */
    AUDIO_IN(0x01),
    /** Núcleo → host: fala a tocar. */
    AUDIO_OUT(0x02),
    /** Fim do stream; payload vazio. */
    AUDIO_END(0x03),
    /** Host → núcleo: PNG ou JPEG. */
    IMAGE(0x10),
    /** Bytes opacos de arquivo. */
    FILE_CHUNK(0x20);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FrameType of(int code) {
        for (FrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("tipo de frame desconhecido: 0x" + Integer.toHexString(code));
    }
}
