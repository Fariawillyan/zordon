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
package zordon.zwp;

import zordon.api.event.EventEnvelope;

/** O que um cliente ZWP recebe sem ter pedido. */
public interface ZwpClientListener {

    /** Evento do barramento do núcleo. */
    default void onEvent(EventEnvelope event) {}

    /** Conexão encerrada, por qualquer motivo. */
    default void onClosed(int code, String reason) {}
}
