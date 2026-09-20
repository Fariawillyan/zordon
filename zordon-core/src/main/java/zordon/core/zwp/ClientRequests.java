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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import zordon.api.trace.Spec;

/**
 * O núcleo pedindo algo a um cliente conectado (docs/api/zwp-protocol.md §5).
 *
 * <p>Falhas chegam como {@link ZwpMethodException}: sem sessão ou sem resposta no
 * prazo é {@code ERR_BRIDGE_UNAVAILABLE}; erro do cliente vem como ele mandou.
 */
@Spec("SPEC-006")
@FunctionalInterface
public interface ClientRequests {

    CompletableFuture<Map<String, Object>> request(
            String sessionId, String method, Map<String, Object> params, Duration timeout);
}
