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
import java.util.concurrent.CompletableFuture;
import zordon.api.trace.Spec;

/**
 * Método servido que responde depois. Existe para quem espera outro cliente —
 * {@code voice.devices} espera o host — sem prender a thread do WebSocket, que
 * pode ser a mesma que vai ler a resposta do host.
 */
@Spec("SPEC-006")
@FunctionalInterface
public interface AsyncMethodHandler {

    CompletableFuture<Map<String, Object>> handle(ZwpSession session, Map<String, Object> params);
}
