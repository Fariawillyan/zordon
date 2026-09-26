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

import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnóstico agregado da memória. */
final class MemoryStats {

    private MemoryStats() {}

    static Map<String, Object> read(ZordonDatabase database, Sql sql, Embedder embedder) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", database.schemaVersion());
        out.put("facts", sql.count("SELECT count(*) FROM fact WHERE superseded_by IS NULL"));
        out.put("sessions", sql.count("SELECT count(*) FROM session"));
        out.put("distillPending", sql.count("SELECT count(*) FROM distill_queue WHERE state = 'pending'"));
        out.put("distillFailed", sql.count("SELECT count(*) FROM distill_queue WHERE state = 'failed'"));
        out.put("vectors", embedder != null);
        return out;
    }
}
