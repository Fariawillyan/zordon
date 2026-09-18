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

import java.time.Duration;

/** Constantes do ZWP v1 (docs/api/zwp-protocol.md §1). */
public final class ZwpProtocol {

    public static final int VERSION = 1;
    public static final String PATH = "/zwp/v1";
    public static final int DEFAULT_PORT = 8777;

    public static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Pings sem pong antes de fechar com {@link ZwpCloseCode#HEARTBEAT_LOST}. */
    public static final int MISSED_HEARTBEATS_BEFORE_CLOSE = 3;

    private ZwpProtocol() {}
}
