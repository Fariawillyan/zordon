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
package zordon.core.voice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** O estado da voz como a tela e o protocolo o veem (SPEC-006 §7). */
final class VoiceSnapshot {

    private VoiceSnapshot() {}

    static Map<String, Object> of(VoiceChoices choices, HostCapture hosts, ClickListening click, WakeStream stream,
            VoiceCommands commands) {
        VoiceMode desired = choices.desired();
        VoiceEngine.Status engineStatus = stream.engineStatus();
        String effective;
        String reason = null;
        if (hosts.disagrees() != null) {
            effective = "unavailable";
            reason = hosts.disagrees();
        } else if (desired == VoiceMode.OFF) {
            effective = VoiceMode.OFF.wire();
        } else if (!hosts.connected()) {
            effective = "unavailable";
            reason = VoiceService.NO_HOST;
        } else if (desired == VoiceMode.PUSH) {
            effective = "unavailable";
            reason = VoiceService.NO_PUSH;
        } else if (engineStatus.state() != VoiceEngine.State.READY) {
            effective = "unavailable";
            reason = engineStatus.reason();
        } else if (!stream.wakeWord()) {
            effective = "unavailable";
            reason = VoiceService.NO_WAKE_WORD;
        } else {
            effective = desired.wire();
        }
        boolean active = !"off".equals(effective) && !"unavailable".equals(effective);

        Map<String, Object> engineState = new LinkedHashMap<>();
        engineState.put("state", engineStatus.state().wire());
        putIfPresent(engineState, "reason", engineStatus.reason());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", desired.wire());
        snapshot.put("effective", effective);
        putIfPresent(snapshot, "reason", reason);
        snapshot.put("activity", activity(click, stream, commands, active).wire());
        putIfPresent(snapshot, "openUntil", choices.openUntil());
        snapshot.put("capture", hosts.captureState());
        snapshot.put("host", hosts.hostState());
        snapshot.put("engine", engineState);
        if (choices.testUntil() != null) {
            snapshot.put("test", Map.of("until", choices.testUntil().toString()));
        }
        if (choices.lastTest() != null) {
            snapshot.put("lastTest", Map.copyOf(choices.lastTest()));
        }
        return snapshot;
    }

    private static VoiceEngine.Activity activity(ClickListening click, WakeStream stream, VoiceCommands commands,
            boolean effectiveActive) {
        VoiceEngine.Activity inStream = stream.commandActivity();
        if (inStream != null) {
            return inStream;
        }
        VoiceEngine.Activity clicked = click.activity();
        if (clicked != null) {
            return clicked;
        }
        if (commands.dispatching()) {
            return VoiceEngine.Activity.THINKING;
        }
        if (stream.speaking()) {
            return VoiceEngine.Activity.SPEAKING;
        }
        return effectiveActive ? stream.engineActivity() : VoiceEngine.Activity.IDLE;
    }

    /** Campo sem valor é omitido: o protocolo não carrega {@code null} (SPEC-006 §7). */
    static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value instanceof Instant instant ? instant.toString() : value);
        }
    }
}
