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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zordon.api.trace.Spec;

/**
 * Decide quando uma frase candidata é dita (SPEC-012 §7). Puro: a hora vem de
 * fora.
 *
 * <ul>
 *   <li>Normal espera uma janela de 2 s; se outra chega nela, só a última vale.
 *   <li>Entre falas normais há pelo menos 4 s.
 *   <li>A mesma frase não se repete em 30 s.
 *   <li>Alta e autorização saem na hora e descartam a normal pendente.
 * </ul>
 */
@Spec("SPEC-012")
public final class VoiceNarrator {

    static final Duration WINDOW = Duration.ofSeconds(2);
    static final Duration MIN_GAP = Duration.ofSeconds(4);
    static final Duration REPEAT = Duration.ofSeconds(30);

    private final Map<String, Instant> said = new HashMap<>();
    private Narration pending;
    private Instant pendingSince;
    private Instant lastNormal;

    /** @return o que dizer agora (normal nunca sai daqui: espera a janela). */
    public List<Narration> offer(Narration narration, Instant now) {
        if (narration.priority() != Narration.Priority.NORMAL) {
            pending = null;
            return release(narration, now);
        }
        if (pending == null) {
            pendingSince = now;
        }
        pending = narration;
        return List.of();
    }

    /** @return a normal pendente, se a janela e o intervalo já passaram. */
    public List<Narration> tick(Instant now) {
        if (pending == null
                || now.isBefore(pendingSince.plus(WINDOW))
                || lastNormal != null && now.isBefore(lastNormal.plus(MIN_GAP))) {
            return List.of();
        }
        Narration next = pending;
        pending = null;
        List<Narration> out = release(next, now);
        if (!out.isEmpty()) {
            lastNormal = now;
        }
        return out;
    }

    private List<Narration> release(Narration narration, Instant now) {
        Instant before = said.get(narration.text());
        if (before != null && now.isBefore(before.plus(REPEAT))) {
            return List.of();
        }
        said.put(narration.text(), now);
        said.values().removeIf(at -> now.isAfter(at.plus(REPEAT)));
        return List.of(narration);
    }
}
