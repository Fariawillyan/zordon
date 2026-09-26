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
import zordon.api.security.RequestOrigin;

/** O teto de cada origem (identity.md §3): o que o risco final significa para quem pediu. */
final class OriginCeiling {

    private final SessionApprovals approvals;

    OriginCeiling(SessionApprovals approvals) {
        this.approvals = approvals;
    }

    /** Agente herda a origem de quem o iniciou. */
    Decision decide(ActionDescriptor action, Principal actor, PermissionEngine.PolicyContext ctx, Risks risks) {
        RequestOrigin origin = actor.origin() == RequestOrigin.AGENT ? RequestOrigin.AUTOMATION : actor.origin();
        return switch (risks.risk()) {
            case GREEN -> green(action, origin, risks);
            case YELLOW -> yellow(action, ctx, origin, risks);
            case RED -> red(action, origin, risks);
        };
    }

    /** GREEN passa direto, menos para o autônomo, que só contém — e conter não escreve. */
    private static Decision green(ActionDescriptor action, RequestOrigin origin, Risks risks) {
        return origin == RequestOrigin.AUTONOMOUS && Risks.writes(action)
                ? new Decision.Deny(risks.risk(), "autônomo só faz contenção reversível")
                : new Decision.Allow(risks.risk(), risks.summary(action), false);
    }

    private Decision yellow(ActionDescriptor action, PermissionEngine.PolicyContext ctx, RequestOrigin origin,
            Risks risks) {
        String why = risks.summary(action);
        return switch (origin) {
            case UI -> approvals.granted(action)
                    ? new Decision.Allow(risks.risk(), why + " — permitido nesta sessão", true)
                    : new Decision.AskUser(risks.risk(), why, PermissionEngine.APPROVAL_TTL, false);
            case VOICE -> new Decision.AskUser(risks.risk(), why + " — confirme na tela",
                    PermissionEngine.APPROVAL_TTL, true);
            case AUTOMATION -> ctx.automationScope().contains(action.tool())
                    ? new Decision.Allow(risks.risk(), why + " — no escopo aprovado da automação", false)
                    : new Decision.Deny(risks.risk(), "fora do escopo aprovado da automação");
            case AUTONOMOUS, AGENT -> new Decision.Deny(risks.risk(), "origem sem autoridade para efeito");
        };
    }

    private static Decision red(ActionDescriptor action, RequestOrigin origin, Risks risks) {
        return switch (origin) {
            case UI, VOICE -> new Decision.AskUser(risks.risk(), risks.summary(action), PermissionEngine.APPROVAL_TTL,
                    true);
            case AUTOMATION, AUTONOMOUS, AGENT ->
                    new Decision.Deny(risks.risk(), "RED nunca roda sem alguém autorizar na tela");
        };
    }
}
