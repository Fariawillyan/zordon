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
 * A ordem da avaliação: recusas imediatas, caminhos, o que a ação e a origem
 * acrescentam, o piso do comando, as travas do contexto e, por fim, o teto da
 * origem. A tabela golden fixa o resultado — mudar a ordem muda o motivo.
 */
final class PolicyEvaluation {

    private final ScopeRules scope;
    private final Redactor redactor;
    private final OriginCeiling ceiling;

    PolicyEvaluation(PathPolicy paths, CommandValidator commands, Redactor redactor, OriginCeiling ceiling) {
        this.scope = new ScopeRules(paths, commands);
        this.redactor = redactor;
        this.ceiling = ceiling;
    }

    Decision evaluate(ActionDescriptor action, Principal actor, PermissionEngine.PolicyContext ctx) {
        Decision refused = Risks.refuseOutright(action, actor, ctx);
        if (refused != null) {
            return refused;
        }
        Risks risks = new Risks(action.baseRisk());
        Decision forbidden = scope.classifyPaths(action, risks);
        if (forbidden != null) {
            return forbidden;
        }
        risks.escalateByAction(action, ctx, redactor.containsSecret(action.args()));
        risks.escalateByOrigin(actor, ctx);
        Decision denied = scope.applyCommand(action, risks);
        if (denied != null) {
            return denied;
        }
        Decision blocked = risks.blocked(ctx);
        return blocked != null ? blocked : ceiling.decide(action, actor, ctx, risks);
    }
}
