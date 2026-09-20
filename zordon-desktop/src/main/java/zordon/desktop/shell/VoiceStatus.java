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

import java.time.Instant;
import java.util.Map;

/**
 * O snapshot de {@code voice.status} / {@code VOICE_STATE} (SPEC-006 §7). Campo
 * ausente no protocolo vira {@code null} aqui.
 */
public record VoiceStatus(
        String mode,
        String effective,
        String reason,
        String activity,
        Instant openUntil,
        String capture,
        Boolean requested,
        Instant confirmedAt,
        boolean hostConnected,
        String device,
        String engine,
        String engineReason,
        Instant testUntil,
        LastTest lastTest) {

    /** Resultado do último teste do microfone (SPEC-009); dBFS ausentes quando falhou. */
    public record LastTest(String verdict, String message, Double peakDbfs, Double averageDbfs) {

        static LastTest from(Object value) {
            if (!(value instanceof Map<?, ?> map) || !(map.get("verdict") instanceof String verdict)) {
                return null;
            }
            return new LastTest(verdict, text(map.get("message"), ""), number(map.get("peakDbfs")),
                    number(map.get("averageDbfs")));
        }

        private static Double number(Object value) {
            return value instanceof Number number ? number.doubleValue() : null;
        }
    }

    public static VoiceStatus from(Map<String, Object> snapshot) {
        Map<?, ?> capture = map(snapshot.get("capture"));
        Map<?, ?> host = map(snapshot.get("host"));
        Map<?, ?> engine = map(snapshot.get("engine"));
        return new VoiceStatus(
                text(snapshot.get("mode"), "off"),
                text(snapshot.get("effective"), "unavailable"),
                text(snapshot.get("reason"), null),
                text(snapshot.get("activity"), "idle"),
                instant(snapshot.get("openUntil")),
                text(capture.get("state"), "unknown"),
                capture.get("requested") instanceof Boolean value ? value : null,
                instant(capture.get("confirmedAt")),
                Boolean.TRUE.equals(host.get("connected")),
                text(host.get("device"), null),
                text(engine.get("state"), "absent"),
                text(engine.get("reason"), null),
                instant(map(snapshot.get("test")).get("until")),
                LastTest.from(snapshot.get("lastTest")));
    }

    public boolean testing() {
        return testUntil != null;
    }

    public boolean captureOn() {
        return "on".equals(capture);
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    private static Instant instant(Object value) {
        return value instanceof String text ? Instant.parse(text) : null;
    }
}
