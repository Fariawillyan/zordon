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
package zordon.api.security;

import java.time.Duration;
import java.util.Objects;

/** O que o motor de permissão decidiu, com o risco efetivo e o motivo legível. */
public sealed interface Decision {

    RiskLevel risk();

    String reason();

    /** @param session vale para (ferramenta, área) até o núcleo reiniciar */
    record Allow(RiskLevel risk, String reason, boolean session) implements Decision {
        public Allow {
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Deny(RiskLevel risk, String reason) implements Decision {
        public Deny {
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** @param perAction RED: a resposta vale só para esta ação, nunca para a sessão */
    record AskUser(RiskLevel risk, String reason, Duration ttl, boolean perAction) implements Decision {
        public AskUser {
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(ttl, "ttl");
        }
    }

    default String wire() {
        return switch (this) {
            case Allow allow -> "allow";
            case Deny deny -> "deny";
            case AskUser ask -> "ask";
        };
    }
}
