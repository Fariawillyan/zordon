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

/** Códigos de fechamento de WebSocket do ZWP (docs/api/zwp-protocol.md §9). */
public final class ZwpCloseCode {

    public static final int PROTOCOL_UNSUPPORTED = 4400;
    public static final int UNAUTHORIZED = 4401;

    /**
     * Handshake trouxe {@code Origin}. Navegadores sempre o enviam e clientes
     * nativos não — é a segunda camada da defesa contra uma aba maliciosa
     * (ADR-0006).
     */
    public static final int ORIGIN_PRESENT = 4403;

    public static final int HEARTBEAT_LOST = 4408;
    public static final int TOO_MANY_MESSAGES = 4429;
    public static final int SHUTTING_DOWN = 4500;

    private ZwpCloseCode() {}
}
