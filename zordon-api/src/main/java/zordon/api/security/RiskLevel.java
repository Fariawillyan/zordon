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

/** Risco de uma ação resolvida (docs/security/model.md §2). A ordem importa: GREEN &lt; YELLOW &lt; RED. */
public enum RiskLevel {
    GREEN,
    YELLOW,
    RED;

    /** Um nível acima, sem passar de RED. */
    public RiskLevel raise() {
        return this == GREEN ? YELLOW : RED;
    }

    public RiskLevel atLeast(RiskLevel floor) {
        return compareTo(floor) >= 0 ? this : floor;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
