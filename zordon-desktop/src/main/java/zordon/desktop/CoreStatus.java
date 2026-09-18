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
package zordon.desktop;

import zordon.zwp.CoreConnection;

/** Como o estado da conexão é apresentado ao usuário. */
public record CoreStatus(CoreConnection.State state, String detail) {

    public static CoreStatus offline(String reason) {
        return new CoreStatus(CoreConnection.State.OFFLINE, reason);
    }

    public String label() {
        return switch (state) {
            case ONLINE -> "CORE ONLINE";
            case CONNECTING -> "CONECTANDO…";
            case OFFLINE -> "NÚCLEO OFFLINE";
        };
    }
}
