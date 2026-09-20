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
package zordon.memory;

import java.time.Instant;

/** O que muda sozinho numa automação (SPEC-025): último disparo, falhas, desativada. */
public interface AutomationStateStore {

    record State(String id, Instant lastFiredAt, int fired, int failures, boolean disabled, String reason) {

        public static State fresh(String id) {
            return new State(id, null, 0, 0, false, null);
        }
    }

    State automationState(String id);

    long automationTokens(java.time.LocalDate day);

    /** Reserva antes de chamar o modelo; queda conserva a reserva por segurança. */
    long reserveAutomationTokens(java.time.LocalDate day, long requested, long ceiling);

    /** Ajusta a reserva ao consumo conhecido ao terminar. */
    void settleAutomationTokens(java.time.LocalDate day, long reserved, long used);

    void automationFired(String id, Instant at);

    /** @return as falhas seguidas depois deste desfecho */
    int automationFinished(String id, boolean ok);

    void automationDisabled(String id, boolean disabled, String reason);
}
