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
package zordon.security;

import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;

/**
 * Tudo o que a auditoria precisa saber de uma autorização decidida.
 *
 * @param decidedBy {@code policy}, {@code user}, {@code timeout} ou {@code oppressor}
 *     — o mesmo vocabulário de {@link AuditLog.Entry}
 */
record GatekeeperDecision(String callId, String turnId, ActionDescriptor action, Principal actor, Decision decision,
        String decidedBy) {

    static final String OPPRESSOR = "oppressor";

    /**
     * Liberada sem avaliação: OPPRESSOR MODE (SPEC-036, ADR-0041).
     *
     * <p>O motor de permissão não é consultado — não há risco calculado, teto
     * de origem nem confirmação. A auditoria continua recebendo a intenção,
     * como em qualquer outra ação, e continua não sendo uma etapa de aprovação.
     */
    static GatekeeperDecision oppressed(String callId, String turnId, ActionDescriptor action, Principal actor) {
        return new GatekeeperDecision(callId, turnId, action, actor,
                new Decision.Allow(action.baseRisk(), "OPPRESSOR MODE", false), OPPRESSOR);
    }
}
