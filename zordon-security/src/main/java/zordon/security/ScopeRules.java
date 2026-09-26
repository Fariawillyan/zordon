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
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;

/** Onde a ação toca e o que ela roda: a política de caminhos e o catálogo de comandos. */
final class ScopeRules {

    private final PathPolicy paths;
    private final CommandValidator commands;

    ScopeRules(PathPolicy paths, CommandValidator commands) {
        this.paths = paths;
        this.commands = commands;
    }

    /** Classifica cada caminho tocado. Devolve a recusa, ou {@code null} e sobe o risco. */
    Decision classifyPaths(ActionDescriptor action, Risks risks) {
        boolean writes = Risks.writes(action);
        for (ZPath path : action.touchedPaths()) {
            PathPolicy.Verdict verdict = paths.classify(path);
            if (verdict.forbidden()) {
                return new Decision.Deny(RiskLevel.RED, "caminho proibido pela política: " + path);
            }
            if (verdict.install() && writes) {
                return new Decision.Deny(RiskLevel.RED, "escrita no caminho de instalação do Zordon: " + path);
            }
            if (verdict.critical()) {
                risks.red("caminho crítico " + path);
            } else if (writes ? !verdict.workspace() : !verdict.readable()) {
                risks.raise("fora das áreas permitidas: " + path);
            }
        }
        return null;
    }

    /** O piso que o validador de comando impõe. Devolve a recusa, ou {@code null}. */
    Decision applyCommand(ActionDescriptor action, Risks risks) {
        if (action.command().isEmpty()) {
            return null;
        }
        return switch (commands.validate(action.command())) {
            case CommandValidator.Validation.Denied denied -> new Decision.Deny(RiskLevel.RED, denied.reason());
            case CommandValidator.Validation.Accepted accepted -> {
                risks.floor(accepted.floor(), accepted.note());
                yield null;
            }
        };
    }
}
