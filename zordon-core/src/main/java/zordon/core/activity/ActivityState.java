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
package zordon.core.activity;

import java.util.Locale;

/** Os estados visuais do núcleo (SPEC-012 §7). Só animação na tela de Voz. */
public enum ActivityState {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    PLANNING,
    EXECUTING,
    AGENTS,
    SPEAKING,
    DONE,
    ATTENTION,
    ERROR;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
