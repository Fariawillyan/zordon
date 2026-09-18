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
package zordon.core.chat;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import zordon.api.TurnId;

/** Uma mensagem já registrada na conversa. */
public record StoredMessage(String role, String text, TurnId turn, Instant ts) {

    public StoredMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(ts, "ts");
    }

    /** Forma que viaja no ZWP. */
    public Map<String, Object> toWire() {
        return Map.of(
                "role", role,
                "text", text,
                "turnId", turn == null ? "" : turn.value(),
                "ts", ts.toString());
    }
}
