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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;
import zordon.api.trace.Spec;

/**
 * O único caminho de uma ação até a execução (SPEC-016 CA-1): classificar,
 * perguntar quando for preciso, gravar a intenção e só então liberar. Quem não
 * tem uma {@link Permit.Granted} não executa, porque {@link ProcessRunner} e as
 * ferramentas exigem uma.
 *
 * <p>Ser o caminho único é o que permite ao OPPRESSOR MODE existir de verdade:
 * o modo desvia aqui, e por isso vale para toda ação do processo, não só para
 * as que alguma tela lembrou de perguntar (SPEC-036).
 */
@Spec("SPEC-016")
public final class Gatekeeper {

    /** O resultado da autorização. Só este pacote cria uma {@code Granted}. */
    public sealed interface Permit {

        String callId();

        Decision decision();

        final class Granted implements Permit {
            private final String callId;
            private final ActionDescriptor action;
            private final Decision decision;

            private Granted(String callId, ActionDescriptor action, Decision decision) {
                this.callId = callId;
                this.action = action;
                this.decision = decision;
            }

            @Override
            public String callId() {
                return callId;
            }

            public ActionDescriptor action() {
                return action;
            }

            @Override
            public Decision decision() {
                return decision;
            }
        }

        record Refused(String callId, Decision decision) implements Permit {}
    }

    private static final Logger log = LoggerFactory.getLogger(Gatekeeper.class);

    private final PermissionEngine engine;
    private final AuditLog audit;
    private final BooleanSupplier oppressor;

    /** Sem OPPRESSOR MODE: toda ação passa pelo motor de permissão. */
    public Gatekeeper(PermissionEngine engine, AuditLog audit) {
        this(engine, audit, () -> false);
    }

    public Gatekeeper(PermissionEngine engine, AuditLog audit, BooleanSupplier oppressor) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.oppressor = Objects.requireNonNull(oppressor, "oppressor");
    }

    public CompletableFuture<Permit> authorize(ActionDescriptor action, Principal actor,
            PermissionEngine.PolicyContext ctx, String turnId) {
        String callId = "call-" + UUID.randomUUID();
        if (oppressor.getAsBoolean() && !ctx.lockdown()) {
            return CompletableFuture.completedFuture(oppress(callId, action, actor, turnId));
        }
        Decision first = engine.evaluate(action, actor, ctx);
        CompletableFuture<Decision> decided;
        String decidedBy;
        if (first instanceof Decision.AskUser ask) {
            decided = engine.requestApproval(action, actor, ask);
            decidedBy = "user";
        } else {
            decided = CompletableFuture.completedFuture(first);
            decidedBy = "policy";
        }
        return decided.thenApply(decision -> {
            String by = decision instanceof Decision.Deny deny && first instanceof Decision.AskUser
                    && deny.reason().startsWith("sem ") ? "timeout" : decidedBy;
            // A intenção é gravada sempre, inclusive a negada (SPEC-014 CA-2).
            audit.begin(new AuditLog.Entry(callId, turnId, actor, action.tool(), action.args(), decision, by));
            log.info("{} {} → {} ({}, {})", callId, action.tool(), decision.wire(), decision.risk().wire(), by);
            if (decision instanceof Decision.Allow) {
                return new Permit.Granted(callId, action, decision);
            }
            audit.complete(callId, AuditLog.Status.CANCELLED, Duration.ZERO, null, decision.reason());
            return new Permit.Refused(callId, decision);
        });
    }

    /**
     * Libera sem avaliar: OPPRESSOR MODE (SPEC-036, ADR-0041).
     *
     * <p>O motor de permissão não é consultado — não há risco calculado, teto
     * de origem nem confirmação. A auditoria continua recebendo a intenção,
     * como em qualquer outra ação, e continua não sendo uma etapa de aprovação:
     * {@code begin} grava e a execução segue.
     *
     * <p>O lockdown é a única coisa que o modo não atravessa, e por isso o
     * desvio olha {@code ctx.lockdown()} antes de chegar aqui. Um kill switch
     * que um modo desliga não é um kill switch — e ele também é acionado
     * sozinho pela defesa quando a cadeia da auditoria aparece quebrada, que é
     * justamente quando a senha digitada antes não prova mais nada. Sair do
     * lockdown continua sendo uma ação na tela (SPEC-015 CA-6).
     */
    private Permit.Granted oppress(String callId, ActionDescriptor action, Principal actor, String turnId) {
        Decision decision = new Decision.Allow(action.baseRisk(), "OPPRESSOR MODE", false);
        audit.begin(new AuditLog.Entry(callId, turnId, actor, action.tool(), action.args(), decision, "oppressor"));
        log.warn("{} {} → allow (OPPRESSOR MODE, sem avaliação)", callId, action.tool());
        return new Permit.Granted(callId, action, decision);
    }

    /** O desfecho de uma execução liberada. */
    public void complete(Permit.Granted permit, AuditLog.Status status, Duration took, String summary, String error) {
        audit.complete(permit.callId(), status, took, summary, error);
    }
}
