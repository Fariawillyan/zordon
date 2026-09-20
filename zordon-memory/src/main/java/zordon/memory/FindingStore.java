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

/** Os achados da defesa (SPEC-026): gravados, atualizados enquanto abertos, nunca apagados. */
public interface FindingStore {

    record FindingRow(String id, String severity, String detector, String subjectKind, String subjectId, String title,
            String rationale, String signalsJson, int count, Instant firstSeen, Instant lastSeen,
            Instant acknowledgedAt) {}

    /** Grava ou atualiza o achado aberto (o mesmo id volta enquanto a janela não fecha). */
    void finding(FindingRow finding);

    List<FindingRow> findings(Instant since, List<String> severities, int limit);

    boolean acknowledgeFinding(String id, Instant at);

    /** Contagem por severidade nas últimas 24 h, para o diagnóstico. */
    Map<String, Object> findingStats();
}
