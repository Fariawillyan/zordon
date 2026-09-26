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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;

/** A parte do {@link Gatekeeper} que decide: política, pergunta ao usuário ou OPPRESSOR MODE. */
final class GatekeeperFlow {

    private final PermissionEngine engine;
    private final BooleanSupplier oppressor;

    GatekeeperFlow(PermissionEngine engine, BooleanSupplier oppressor) {
        this.engine = engine;
        this.oppressor = oppressor;
    }

    CompletableFuture<GatekeeperDecision> authorize(ActionDescriptor action, Principal actor,
            PermissionEngine.PolicyContext ctx, String turnId) {
        String callId = "call-" + UUID.randomUUID();
        // O lockdown é a única coisa que o modo não atravessa. Um kill switch
        // que um modo desliga não é um kill switch — e ele também é acionado
        // sozinho pela defesa quando a cadeia da auditoria aparece quebrada, que
        // é justamente quando a senha digitada antes não prova mais nada. Sair do
        // lockdown continua sendo uma ação na tela (SPEC-015 CA-6).
        if (oppressor.getAsBoolean() && !ctx.lockdown()) {
            return CompletableFuture.completedFuture(GatekeeperDecision.oppressed(callId, turnId, action, actor));
        }
        Decision first = engine.evaluate(action, actor, ctx);
        if (!(first instanceof Decision.AskUser ask)) {
            return CompletableFuture.completedFuture(
                    new GatekeeperDecision(callId, turnId, action, actor, first, "policy"));
        }
        return engine.requestApproval(action, actor, ask).thenApply(decision ->
                new GatekeeperDecision(callId, turnId, action, actor, decision, answeredBy(decision)));
    }

    /** Uma pergunta sem resposta no prazo (ou sem tela) vira negação — e a auditoria diz que foi o prazo. */
    private static String answeredBy(Decision decision) {
        return decision instanceof Decision.Deny deny && deny.reason().startsWith("sem ") ? "timeout" : "user";
    }
}
