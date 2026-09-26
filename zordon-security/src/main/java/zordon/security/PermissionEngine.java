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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;

/**
 * O contrato central de segurança (docs/api/core-interfaces.md §5, SPEC-014).
 */
@Spec("SPEC-014")
public interface PermissionEngine {

    /** Tempo para o usuário decidir; depois disso, nega (docs/security/model.md §2). */
    Duration APPROVAL_TTL = Duration.ofSeconds(60);

    /**
     * O que muda o risco além da ação em si.
     *
     * @param lockdown o sistema está em Defense Lockdown: nada acima de GREEN
     * @param breakerOpen o disjuntor do ator está aberto: negado
     * @param tainted o turno leu conteúdo sensível: saída de dados vira RED
     * @param userPresent há alguém na frente da máquina (automação sem usuário sobe um nível)
     * @param newTool ferramenta de servidor MCP novo ou nunca usada
     * @param automationScope ferramentas que a automação em curso tem aprovadas
     * @param ceiling o teto do agente que pede; {@code null} sem agente (SPEC-022). Acima dele, nega:
     *     o agente não consegue nem pedir
     */
    record PolicyContext(boolean lockdown, boolean breakerOpen, boolean tainted, boolean userPresent,
            boolean newTool, Set<String> automationScope, RiskLevel ceiling) {

        public PolicyContext {
            automationScope = Set.copyOf(automationScope);
        }

        public static PolicyContext interactive() {
            return new PolicyContext(false, false, false, true, false, Set.of(), null);
        }
    }

    /** Resposta do usuário a um pedido. {@code SESSION} nunca vale para RED. */
    enum Approval { ONCE, SESSION, DENY }

    /** Quem mostra o pedido ao usuário: o desktop (SPEC-015). */
    interface Approver {
        record ApprovalRequest(ActionDescriptor action, Principal actor, RiskLevel risk, Duration ttl,
                boolean perAction) {}

        CompletableFuture<Approval> ask(ApprovalRequest request);
    }

    /** Classifica. Puro, determinístico e sem efeito colateral. */
    Decision evaluate(ActionDescriptor action, Principal actor, PolicyContext ctx);

    /** Pergunta ao usuário. Sem aprovador, ou sem resposta no prazo, nega. */
    CompletableFuture<Decision> requestApproval(ActionDescriptor action, Principal actor, Decision.AskUser ask);
}
