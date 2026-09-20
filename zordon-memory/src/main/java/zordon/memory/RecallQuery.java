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
package zordon.memory;

import java.time.Instant;
import java.util.Set;

/**
 * Uma busca na memória.
 *
 * @param since início da janela, inclusive; {@code null} sem limite
 * @param until fim da janela, exclusive; {@code null} sem limite
 */
public record RecallQuery(String text, Set<FactKind> kinds, Instant since, Instant until, int limit) {

    public RecallQuery {
        text = text == null ? "" : text;
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        limit = limit <= 0 ? 10 : Math.min(limit, 50);
    }

    public static RecallQuery of(String text, int limit) {
        return new RecallQuery(text, Set.of(), null, null, limit);
    }
}
