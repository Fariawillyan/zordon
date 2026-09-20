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

import java.util.Locale;

/** Modos de {@code voice.setMode} (docs/specs/voice/design.md §3). */
public enum VoiceMode {
    OFF,
    WAKE,
    PUSH,
    /** Temporário: nunca persistido (SPEC-006 §5). */
    OPEN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static VoiceMode parse(Object value) {
        if (value instanceof String text) {
            for (VoiceMode mode : values()) {
                if (mode.wire().equals(text)) {
                    return mode;
                }
            }
        }
        throw new IllegalArgumentException("modo de voz inválido: " + value + " (use off, wake, push ou open)");
    }
}
