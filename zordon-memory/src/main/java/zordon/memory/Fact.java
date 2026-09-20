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

/**
 * Um fato de longo prazo. {@code provenance} é o turno de origem: toda lembrança
 * responde "onde foi que eu disse isso?" (Interfaces §8).
 *
 * @param source quem escreveu: {@code user}, {@code work} ou {@code distill}
 */
public record Fact(
        String id,
        FactKind kind,
        String subject,
        String content,
        double confidence,
        Instant observedAt,
        Instant expiresAt,
        String provenance,
        String source,
        int accessCount,
        String supersededBy) {

    public boolean active(Instant now) {
        return supersededBy == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
