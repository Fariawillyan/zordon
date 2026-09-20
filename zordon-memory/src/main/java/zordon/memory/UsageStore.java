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

import java.time.LocalDate;
import java.util.Map;

/** Quanto foi gasto (SPEC-029). Some por dia, provider, modelo e ator. */
public interface UsageStore {

    void recordUsage(LocalDate day, String provider, String model, String actor, long input, long output);

    /** Resumo dos últimos {@code days} dias, com o total e a quebra por ator. */
    Map<String, Object> usageSummary(int days);
}
