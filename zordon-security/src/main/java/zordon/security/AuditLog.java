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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import zordon.api.security.Decision;
import zordon.api.security.Principal;
import zordon.api.trace.Spec;

/**
 * Registro append-only com cadeia de hash de toda ação tentada e do desfecho
 * (docs/security/model.md §7, SPEC-014 CA-1, CA-2).
 *
 * <p>Nada é atualizado: {@link #begin} grava a intenção antes de executar e
 * {@link #complete} grava o desfecho numa linha nova, ligada pelo {@code callId}.
 */
@Spec("SPEC-014")
public interface AuditLog extends AutoCloseable {

    enum Status {
        STARTED,
        OK,
        FAILED,
        CANCELLED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A intenção de uma ação e a decisão sobre ela.
     *
     * @param decidedBy {@code policy}, {@code user}, {@code timeout}, {@code ceiling}
     *     ou {@code oppressor} (liberado sem avaliação, SPEC-036)
     */
    record Entry(String callId, String turnId, Principal principal, String tool, Map<String, Object> args,
            Decision decision, String decidedBy) {

        public Entry {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(tool, "tool");
            args = Map.copyOf(args);
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(decidedBy, "decidedBy");
        }
    }

    /** @param firstBroken id da primeira linha inválida, ou {@code -1} */
    record Verification(boolean ok, int checked, long firstBroken) {}

    /** Dados do desfecho, agrupados para manter a operação auditável e estável. */
    record Completion(Status status, Duration took, String summary, String error) {
        public Completion {
            Objects.requireNonNull(status, "status");
        }
    }

    /** Grava a intenção. Sempre, inclusive quando a decisão é negar. @return o id da linha */
    long begin(Entry entry);

    /** Grava o desfecho numa linha nova. */
    void complete(String callId, Completion completion);

    /** Confere as últimas {@code lastN} linhas: cada hash e o encadeamento. */
    Verification verify(int lastN);

    long entries();

    @Override
    void close();
}
