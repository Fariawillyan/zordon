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
package zordon.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;

class LiveTraceTest {

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-012/CA-9")
    @Test
    void todoEventoMenosONivelVaiParaOJsonlDoDiaSoAcrescentando() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-18T18:00:00Z"), ZoneOffset.UTC);
        LiveTrace trace = new LiveTrace(home.resolve("trace"), clock);
        Instant at = clock.instant();

        trace.write(new EventEnvelope(1, at, EventType.USER_COMMAND, Map.of("turnId", "t1", "text", "que horas são")));
        trace.write(new EventEnvelope(2, at, EventType.VOICE_LEVEL, Map.of("rms", -30.0, "peak", -12.0)));
        trace.write(new EventEnvelope(3, at, EventType.VOICE_NARRATION,
                Map.of("text", "Microfone funcionando.", "priority", "normal", "category", "resultado")));

        Path file = home.resolve("trace").resolve("2026-09-18.jsonl");
        assertThat(trace.today()).isEqualTo(file);
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("\"seq\":1").contains("USER_COMMAND").contains("que horas são");
        assertThat(lines.get(1)).contains("VOICE_NARRATION");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("trace"))))
                .isEqualTo("rwx------");

        trace.write(new EventEnvelope(4, at, EventType.AI_THINKING, Map.of("turnId", "t1")));
        assertThat(Files.readAllLines(file)).hasSize(3).startsWith(lines.toArray(String[]::new));
    }
}
