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
package zordon.core.zwp;

import java.util.Map;
import zordon.api.trace.Spec;

/** O núcleo avisando um cliente, sem esperar resposta (ex.: {@code audio.credit}). */
@Spec("SPEC-009")
@FunctionalInterface
public interface ClientNotifier {

    /** @return falso se a sessão não está conectada. */
    boolean notify(String sessionId, String method, Map<String, Object> params);
}
