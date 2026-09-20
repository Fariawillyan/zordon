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

import java.util.Objects;

/**
 * Quem pede a ação.
 *
 * @param actor {@code user}, {@code agent:<id>} ou {@code automation:<id>}
 * @param origin de onde veio a ordem; para um agente, a de quem o iniciou
 * @param delegated se um agente pediu em nome de outro (sobe um nível de risco)
 */
public record Principal(String actor, RequestOrigin origin, boolean delegated) {

    public Principal {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin: ação sem origem é erro de programação (identity.md §6)");
    }

    public static Principal user(RequestOrigin origin) {
        return new Principal("user", origin, false);
    }
}
