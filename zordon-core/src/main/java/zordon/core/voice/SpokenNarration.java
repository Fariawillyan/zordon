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

import java.util.regex.Pattern;
import zordon.core.activity.Narration;

/** O jeito de dizer uma narração: o estilo pela prioridade, e se ela contém a palavra de ativação. */
final class SpokenNarration {

    private static final Pattern WAKE_WORD = Pattern.compile("z[oóô]rd[oõ]", Pattern.CASE_INSENSITIVE);

    private SpokenNarration() {}

    /** A fala contém a palavra: tocá-la não pode acordar o próprio Zordon (SPEC-013 CA-9). */
    static boolean mentionsWakeWord(String text) {
        return WAKE_WORD.matcher(text).find();
    }

    static VoiceEngine.SpeechStyle style(Narration narration) {
        if (narration.priority() == Narration.Priority.AUTHORIZATION) {
            return VoiceEngine.SpeechStyle.authorization();
        }
        if ("erro".equals(narration.category())) {
            return VoiceEngine.SpeechStyle.error();
        }
        return narration.priority() == Narration.Priority.HIGH
                ? VoiceEngine.SpeechStyle.high()
                : VoiceEngine.SpeechStyle.normal();
    }
}
