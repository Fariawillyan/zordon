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
package zordon.defense;

import java.time.Instant;
import zordon.api.security.Severity;
import zordon.api.trace.Spec;

/**
 * Toda iniciativa de defesa vira um evento imutável (Defesa §11). A invariante mora
 * no construtor: ação executada sem mensagem ao usuário é impossível de construir.
 *
 * @param executed {@code NONE} quando só observou
 * @param outcome {@code CONTAINED}, {@code BLOCKED}, {@code OBSERVED} ou {@code FAILED}
 * @param authorization {@code AUTO_CONTAINMENT}, {@code USER}, {@code POLICY} ou {@code DENIED}
 */
@Spec("SPEC-027")
public record SecurityEvent(String id, Instant ts, Severity severity, String detector, String subject,
        String findingId, String proposed, String executed, String outcome, String authorization,
        boolean rollbackAvailable, String userMessageId) {

    public static final String NONE = "NONE";

    public SecurityEvent {
        if (!NONE.equals(executed) && (userMessageId == null || userMessageId.isBlank())) {
            // Se a defesa agiu, o usuário soube. Sem isso, não existe "nenhuma iniciativa silenciosa".
            throw new IllegalArgumentException("ação executada sem mensagem ao usuário: " + executed);
        }
        if (executed == null || outcome == null || authorization == null || subject == null) {
            throw new IllegalArgumentException("evento de segurança incompleto");
        }
    }

    public static SecurityEvent observed(String id, Instant ts, Severity severity, String detector, String subject,
            String findingId, String proposed) {
        return new SecurityEvent(id, ts, severity, detector, subject, findingId, proposed, NONE, "OBSERVED",
                "POLICY", false, null);
    }
}
