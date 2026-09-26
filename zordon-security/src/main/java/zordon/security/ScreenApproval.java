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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;

/** Pergunta à tela, com prazo. Sem tela, sem resposta ou com falha, a resposta é negação. */
final class ScreenApproval {

    private final Supplier<PermissionEngine.Approver> approver;
    private final SessionApprovals approvals;

    ScreenApproval(Supplier<PermissionEngine.Approver> approver, SessionApprovals approvals) {
        this.approver = approver;
        this.approvals = approvals;
    }

    CompletableFuture<Decision> request(ActionDescriptor action, Principal actor, Decision.AskUser ask) {
        PermissionEngine.Approver current = approver.get();
        if (current == null) {
            return CompletableFuture.completedFuture(new Decision.Deny(ask.risk(), "nenhuma tela para autorizar"));
        }
        return current.ask(new PermissionEngine.Approver.ApprovalRequest(action, actor, ask.risk(), ask.ttl(),
                        ask.perAction()))
                .orTimeout(ask.ttl().toMillis(), TimeUnit.MILLISECONDS)
                .handle((approval, failure) -> approvals.settle(action, ask, approval, failure));
    }
}
