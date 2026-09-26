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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;

/**
 * O risco em construção durante uma avaliação, e as regras que o sobem a
 * partir da ação, do contexto e da origem (docs/security/model.md §2).
 *
 * <p>São dois números, e a diferença importa: {@code risk} é o do pedido e
 * decide se o usuário confirma; {@code intrinsic} é o da ação em si, sem o
 * que a origem acrescenta, e é contra ele que vale o teto do agente. Um
 * sub-agente de teto GREEN ainda lê; o nível extra da delegação só muda a
 * confirmação (SPEC-022).
 */
final class Risks {

    private static final Set<Effect> WRITES = Set.of(Effect.WRITE_FS, Effect.QUARANTINE_FS, Effect.MODIFY_SELF,
            Effect.MODIFY_PROJECT, Effect.MODIFY_SYSTEM, Effect.MODIFY_CREDENTIALS, Effect.MODIFY_TRUST_KERNEL);

    private RiskLevel risk;
    private RiskLevel intrinsic;
    private final List<String> why = new ArrayList<>();

    Risks(RiskLevel base) {
        this.risk = base;
        this.intrinsic = base;
    }

    static boolean writes(ActionDescriptor action) {
        return action.effects().stream().anyMatch(WRITES::contains);
    }

    /** As recusas que não dependem de nada mais. {@code null} quando não há. */
    static Decision refuseOutright(ActionDescriptor action, Principal actor, PermissionEngine.PolicyContext ctx) {
        if (action.effects().contains(Effect.MODIFY_TRUST_KERNEL)) {
            return new Decision.Deny(RiskLevel.RED, "núcleo de confiança: só proposto por PR, nunca aplicado");
        }
        if (ctx.breakerOpen()) {
            return new Decision.Deny(action.baseRisk(), "disjuntor aberto para " + actor.actor());
        }
        return null;
    }

    RiskLevel risk() {
        return risk;
    }

    /** O que a ação em si acrescenta ao risco. */
    void escalateByAction(ActionDescriptor action, PermissionEngine.PolicyContext ctx, boolean secret) {
        if (action.targets() > DefaultPermissionEngine.TARGET_LIMIT) {
            raise(action.targets() + " alvos");
        }
        if (secret) {
            red("argumento contém segredo");
        }
        if (ctx.newTool()) {
            raise("ferramenta nova");
        }
        if (action.effects().contains(Effect.MODIFY_SELF) || action.effects().contains(Effect.MODIFY_PROJECT)) {
            atLeast(RiskLevel.YELLOW);
        }
        if (ctx.tainted() && (action.effects().contains(Effect.EXPORT_DATA)
                || action.effects().contains(Effect.NETWORK))) {
            red("turno contaminado enviando dados");
        }
    }

    /** O que a origem do pedido acrescenta — sem tocar no risco intrínseco. */
    void escalateByOrigin(Principal actor, PermissionEngine.PolicyContext ctx) {
        if (actor.origin() == RequestOrigin.AUTOMATION && !ctx.userPresent()) {
            raiseRequest("automação sem usuário presente");
        }
        if (actor.delegated()) {
            raiseRequest("pedido por agente delegado");
        }
    }

    /** As travas do contexto que valem depois do risco calculado. {@code null} quando nenhuma trava. */
    Decision blocked(PermissionEngine.PolicyContext ctx) {
        if (ctx.ceiling() != null && intrinsic.compareTo(ctx.ceiling()) > 0) {
            // Teto do agente é trava dura (Interfaces §7): nem chega a perguntar ao usuário.
            return new Decision.Deny(intrinsic, "acima do teto do agente (" + ctx.ceiling().wire() + ")");
        }
        if (ctx.lockdown() && risk != RiskLevel.GREEN) {
            return new Decision.Deny(risk, "Zordon em lockdown: só leitura");
        }
        return null;
    }

    /** A ação em si ficou mais arriscada: sobe os dois. */
    void raise(String reason) {
        risk = risk.raise();
        intrinsic = intrinsic.raise();
        why.add(reason);
    }

    /** Só quem pediu mudou: sobe o do pedido, não o da ação. */
    void raiseRequest(String reason) {
        risk = risk.raise();
        why.add(reason);
    }

    void red(String reason) {
        risk = RiskLevel.RED;
        intrinsic = RiskLevel.RED;
        why.add(reason);
    }

    void atLeast(RiskLevel level) {
        risk = risk.atLeast(level);
        intrinsic = intrinsic.atLeast(level);
    }

    /** Piso vindo do validador de comando: só vira motivo se de fato subiu o pedido. */
    void floor(RiskLevel level, String note) {
        if (level.compareTo(risk) > 0) {
            risk = level;
            why.add(note);
        }
        if (level.compareTo(intrinsic) > 0) {
            intrinsic = level;
        }
    }

    String summary(ActionDescriptor action) {
        return why.isEmpty() ? action.humanSummary() : action.humanSummary() + " (" + String.join("; ", why) + ")";
    }
}
