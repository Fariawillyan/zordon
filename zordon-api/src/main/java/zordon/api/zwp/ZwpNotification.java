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
package zordon.api.zwp;

import java.util.Map;
import java.util.Objects;

/** Notificação JSON-RPC: não espera resposta. É assim que eventos viajam. */
public record ZwpNotification(String method, Map<String, Object> params) implements ZwpMessage {

    /** Método sob o qual todo evento do barramento é publicado (ZWP §6). */
    public static final String EVENT_METHOD = "event";

    public ZwpNotification {
        Objects.requireNonNull(method, "method");
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
