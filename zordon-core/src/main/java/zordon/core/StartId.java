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
package zordon.core;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Identificador da execução do núcleo, no formato ULID.
 *
 * <p>Ordenável por tempo e único por inicialização: é ele que diz ao cliente que o
 * núcleo reiniciou e que não há continuidade a assumir (ADR-0011).
 */
public final class StartId {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generate() {
        return generate(Instant.now(), RANDOM);
    }

    static String generate(Instant when, SecureRandom random) {
        byte[] bytes = new byte[16];
        long millis = when.toEpochMilli();
        for (int i = 5; i >= 0; i--) {
            bytes[i] = (byte) (millis & 0xFF);
            millis >>>= 8;
        }
        byte[] entropy = new byte[10];
        random.nextBytes(entropy);
        System.arraycopy(entropy, 0, bytes, 6, entropy.length);
        return encode(bytes);
    }

    private static String encode(byte[] bytes) {
        StringBuilder text = new StringBuilder(26);
        int buffer = 0;
        // 26 caracteres carregam 130 bits e o ULID tem 128: os dois bits de
        // enchimento ficam no topo. Colocá-los no fim deslocaria o carimbo de tempo
        // e destruiria a ordenação lexicográfica por data, que é a razão do formato.
        int bits = 2;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                text.append(CROCKFORD[(buffer >>> bits) & 0x1F]);
            }
        }
        return text.toString();
    }

    private StartId() {}
}
