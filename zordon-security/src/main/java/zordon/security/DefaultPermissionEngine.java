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
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;
import zordon.api.trace.Spec;

/**
 * Risco = piso da ferramenta + escalonamento por argumento
 * (docs/security/model.md §2); decisão = risco × teto da origem
 * (docs/security/identity.md §3). A tabela golden fixa o resultado.
 *
 * <p>A avaliação fica em {@link PolicyEvaluation}; a pergunta à tela, em
 * {@link ScreenApproval}. Quem monta é {@link PermissionEngines#standard}.
 */
@Spec("SPEC-014")
public final class DefaultPermissionEngine implements PermissionEngine {

    /** Glob que casa mais alvos que isto sobe um nível. */
    public static final int TARGET_LIMIT = 20;

    private final PolicyEvaluation evaluation;
    private final ScreenApproval screen;

    DefaultPermissionEngine(PolicyEvaluation evaluation, ScreenApproval screen) {
        this.evaluation = evaluation;
        this.screen = screen;
    }

    @Override
    public Decision evaluate(ActionDescriptor action, Principal actor, PolicyContext ctx) {
        Objects.requireNonNull(actor, "principal");
        return evaluation.evaluate(action, actor, ctx);
    }

    @Override
    public CompletableFuture<Decision> requestApproval(ActionDescriptor action, Principal actor, Decision.AskUser ask) {
        return screen.request(action, actor, ask);
    }
}
