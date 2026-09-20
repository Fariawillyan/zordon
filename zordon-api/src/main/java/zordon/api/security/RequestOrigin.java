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
package zordon.api.security;

import java.util.Locale;

/** De onde veio a ordem (ADR-0030, docs/security/identity.md §2). Limita o risco máximo. */
public enum RequestOrigin {
    /** Janela do desktop: texto, clique, diálogo. */
    UI,
    /** Microfone: qualquer pessoa na sala pode falar. */
    VOICE,
    /** Gatilho aprovado, sem ninguém presente. */
    AUTOMATION,
    /** Delegação entre agentes: herda a origem de quem iniciou. */
    AGENT,
    /** Reação da defesa: só contenção reversível. */
    AUTONOMOUS;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
