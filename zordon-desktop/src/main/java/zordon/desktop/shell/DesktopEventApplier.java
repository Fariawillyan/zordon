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
package zordon.desktop.shell;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import zordon.api.event.EventEnvelope;

/** Os eventos do protocolo aplicados ao estado da tela. */
final class DesktopEventApplier {

    private DesktopEventApplier() {}

    static void apply(DesktopState state, EventEnvelope event, Clock clock) {
        state.connection().lastSyncProperty().set(Instant.now(clock));
        Map<String, Object> payload = event.payload();
        switch (event.type()) {
            case USER_COMMAND -> {
                state.conversation().turnRunningProperty().set(true);
                state.conversation().currentTurnIdProperty().set(String.valueOf(payload.get("turnId")));
            }
            case AI_THINKING -> state.conversation().turnRunningProperty().set(true);
            case AI_ERROR -> state.conversation().turnRunningProperty().set(false);
            case VOICE_STATE -> state.voice().apply(payload);
            case ACTIVITY_STATE -> state.voice().activityProperty().set(String.valueOf(payload.getOrDefault("state", "idle")));
            case VOICE_LEVEL -> state.voice().levelProperty().set(new double[] {number(payload.get("rms")),
                number(payload.get("peak")), number(payload.getOrDefault("bass", -90)),
                number(payload.getOrDefault("mid", -90)), number(payload.getOrDefault("treble", -90))});
            case LOCKDOWN_ENTERED -> state.security().lockdown(true, String.valueOf(payload.getOrDefault("reason", "")));
            case LOCKDOWN_EXITED -> state.security().lockdown(false, "");
            // Se entrou, é porque havia senha: os dois passam a valer.
            case OPPRESSOR_ENTERED -> state.security().oppressorStatus(true, true);
            case OPPRESSOR_EXITED -> state.security().oppressorProperty().set(false);
            case SECURITY_NOTIFICATION -> state.security().notification(payload);
            case VOICE_STOPPED -> state.voice().transcription(payload, clock);
            case AI_RESPONSE -> state.conversation().response(payload);
            default -> { }
        }
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : -90;
    }
}
