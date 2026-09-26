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

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.Decision;

/**
 * A parte do {@link Gatekeeper} que registra: grava a intenção e só então emite
 * a licença. A auditoria nunca é etapa de aprovação — {@code begin} grava e a
 * execução segue.
 */
final class GatekeeperAudit {

    private static final Logger log = LoggerFactory.getLogger(Gatekeeper.class);

    private final AuditLog audit;

    GatekeeperAudit(AuditLog audit) {
        this.audit = audit;
    }

    Gatekeeper.Permit finish(GatekeeperDecision decided) {
        Decision decision = decided.decision();
        // A intenção é gravada sempre, inclusive a negada (SPEC-014 CA-2).
        audit.begin(new AuditLog.Entry(decided.callId(), decided.turnId(), decided.actor(), decided.action().tool(),
                decided.action().args(), decision, decided.decidedBy()));
        if (GatekeeperDecision.OPPRESSOR.equals(decided.decidedBy())) {
            log.warn("{} {} → allow (OPPRESSOR MODE, sem avaliação)", decided.callId(), decided.action().tool());
        } else {
            log.info("{} {} → {} ({}, {})", decided.callId(), decided.action().tool(), decision.wire(),
                    decision.risk().wire(), decided.decidedBy());
        }
        if (decision instanceof Decision.Allow) {
            return new Gatekeeper.Permit.Granted(decided.callId(), decided.action(), decision, audit);
        }
        audit.complete(decided.callId(),
                new AuditLog.Completion(AuditLog.Status.CANCELLED, Duration.ZERO, null, decision.reason()));
        return new Gatekeeper.Permit.Refused(decided.callId(), decision);
    }
}
