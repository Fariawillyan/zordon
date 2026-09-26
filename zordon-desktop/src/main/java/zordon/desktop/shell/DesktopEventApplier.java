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

/** Applies protocol events to the desktop state without bloating the state holder. */
final class DesktopEventApplier {

    private DesktopEventApplier() {}

    static void apply(DesktopState state, EventEnvelope event, Clock clock) {
        state.lastSyncProperty().set(Instant.now(clock));
        Map<String, Object> payload = event.payload();
        switch (event.type()) {
            case USER_COMMAND -> {
                state.turnRunningProperty().set(true);
                state.currentTurnIdProperty().set(String.valueOf(payload.get("turnId")));
            }
            case AI_THINKING -> state.turnRunningProperty().set(true);
            case AI_ERROR -> state.turnRunningProperty().set(false);
            case VOICE_STATE -> state.voice(payload);
            case ACTIVITY_STATE -> state.activityProperty().set(String.valueOf(payload.getOrDefault("state", "idle")));
            case VOICE_LEVEL -> state.voiceLevelProperty().set(new double[] {number(payload.get("rms")),
                number(payload.get("peak")), number(payload.getOrDefault("bass", -90)),
                number(payload.getOrDefault("mid", -90)), number(payload.getOrDefault("treble", -90))});
            case LOCKDOWN_ENTERED -> state.lockdown(true, String.valueOf(payload.getOrDefault("reason", "")));
            case LOCKDOWN_EXITED -> state.lockdown(false, "");
            case OPPRESSOR_ENTERED -> state.oppressorStatus(true, true);
            case OPPRESSOR_EXITED -> state.oppressorProperty().set(false);
            case SECURITY_NOTIFICATION -> state.notification(payload);
            case VOICE_STOPPED -> state.transcription(payload, clock);
            case AI_RESPONSE -> state.response(payload);
            default -> { }
        }
    }

    private static double number(Object value) { return value instanceof Number number ? number.doubleValue() : -90; }
}
