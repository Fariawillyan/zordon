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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
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
 *
 * <p>A decisão fica em {@link GatekeeperFlow} e o registro em
 * {@link GatekeeperAudit}; esta classe só os liga.
 */
@Spec("SPEC-016")
public final class Gatekeeper {

    /** O resultado da autorização. Só este pacote cria uma {@code Granted}. */
    public sealed interface Permit {

        String callId();

        Decision decision();

        /**
         * A licença para executar. Quem executa grava o desfecho nela, e ele
         * fica ligado na auditoria à intenção que a liberou.
         */
        final class Granted implements Permit {
            private final String callId;
            private final ActionDescriptor action;
            private final Decision decision;
            private final AuditLog audit;

            Granted(String callId, ActionDescriptor action, Decision decision, AuditLog audit) {
                this.callId = callId;
                this.action = action;
                this.decision = decision;
                this.audit = audit;
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

            /** O desfecho da execução liberada, numa linha nova da auditoria (SPEC-014 CA-2). */
            public void complete(AuditLog.Completion completion) {
                audit.complete(callId, completion);
            }
        }

        record Refused(String callId, Decision decision) implements Permit {}
    }

    private final GatekeeperFlow flow;
    private final GatekeeperAudit recorder;

    /** Sem OPPRESSOR MODE: toda ação passa pelo motor de permissão. */
    public Gatekeeper(PermissionEngine engine, AuditLog audit) {
        this(engine, audit, () -> false);
    }

    public Gatekeeper(PermissionEngine engine, AuditLog audit, BooleanSupplier oppressor) {
        this.flow = new GatekeeperFlow(Objects.requireNonNull(engine, "engine"),
                Objects.requireNonNull(oppressor, "oppressor"));
        this.recorder = new GatekeeperAudit(Objects.requireNonNull(audit, "audit"));
    }

    public CompletableFuture<Permit> authorize(ActionDescriptor action, Principal actor,
            PermissionEngine.PolicyContext ctx, String turnId) {
        return flow.authorize(action, actor, ctx, turnId).thenApply(recorder::finish);
    }
}
