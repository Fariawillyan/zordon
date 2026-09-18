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

/** Faixa de versões de protocolo que um lado aceita. */
public record ProtocolRange(int min, int max) {

    public ProtocolRange {
        if (min > max) {
            throw new IllegalArgumentException("faixa de protocolo invertida: " + min + ">" + max);
        }
    }

    public static ProtocolRange exactly(int version) {
        return new ProtocolRange(version, version);
    }

    public boolean supports(int version) {
        return version >= min && version <= max;
    }
}
