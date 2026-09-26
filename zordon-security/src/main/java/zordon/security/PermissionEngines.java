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
import java.util.function.Supplier;

/** Monta o motor de permissão padrão (SPEC-014). */
public final class PermissionEngines {

    private PermissionEngines() {}

    /**
     * @param approver a tela que autoriza; devolve {@code null} enquanto nenhuma
     *     estiver conectada, e aí YELLOW e RED viram negação
     */
    public static PermissionEngine standard(PathPolicy paths, CommandValidator commands, Redactor redactor,
            Supplier<PermissionEngine.Approver> approver) {
        SessionApprovals approvals = new SessionApprovals();
        return new DefaultPermissionEngine(
                new PolicyEvaluation(Objects.requireNonNull(paths, "paths"),
                        Objects.requireNonNull(commands, "commands"), Objects.requireNonNull(redactor, "redactor"),
                        new OriginCeiling(approvals)),
                new ScreenApproval(Objects.requireNonNull(approver, "approver"), approvals));
    }
}
