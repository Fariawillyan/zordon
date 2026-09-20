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
import java.util.List;
import java.util.Map;

/** O histórico da defesa (SPEC-027): append-only, em cadeia de hash. */
public interface SecurityEventStore {

    record SecurityEventRow(String eventId, Instant ts, String severity, String detector, String subject,
            String findingId, String proposed, String executed, String outcome, String authorization,
            boolean rollbackAvailable, String userMessageId) {}

    /** @return o hash desta linha, que encadeia a próxima */
    String securityEvent(SecurityEventRow event);

    List<Map<String, Object>> securityEvents(int limit);

    /** A cadeia fecha? Mesma ideia da auditoria (SPEC-014). */
    boolean securityChainOk();
}
