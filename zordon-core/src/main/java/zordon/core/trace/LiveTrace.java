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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.Spec;
import zordon.core.event.EventSubscription;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;

/**
 * O registro completo de tudo o que passou pelo barramento (SPEC-012 §7): um
 * JSONL por dia em {@code ~/.zordon/trace}, só acrescentando. É o que o modo
 * técnico mostra; a tela de Voz nunca.
 *
 * <p>Nada aqui apaga: arquivos de dias anteriores ficam (ADR-0015). Uma lacuna
 * — eventos descartados porque o disco não acompanhou — vira uma linha
 * {@code {"gap": ...}}, para o trace nunca parecer completo sem ser.
 */
@Spec("SPEC-012")
public final class LiveTrace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveTrace.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final Path dir;
    private final Clock clock;
    private EventSubscription subscription;
    private volatile long lastWarning;

    public LiveTrace(Path dir, Clock clock) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start(ZordonEventBus bus) {
        subscription = bus.subscribe("trace", Topic.ALL, QueuePolicy.dropOldest(8_192), this::write);
    }

    public Path dir() {
        return dir;
    }

    public Path today() {
        return dir.resolve(LocalDate.now(clock) + ".jsonl");
    }

    /** Uma linha por evento, menos {@code VOICE_LEVEL}: métricas transitórias 20 vezes por segundo. */
    public void write(EventEnvelope event) {
        if (event.type() == EventType.VOICE_LEVEL) {
            return;
        }
        try {
            if (subscription != null && subscription.consumeGapFlag()) {
                append(Map.of("gap", true, "dropped", subscription.droppedCount()));
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("seq", event.seq());
            line.put("ts", event.ts().toString());
            line.put("type", event.type().name());
            line.put("topic", event.topic());
            line.put("payload", event.payload());
            append(line);
        } catch (IOException e) {
            long now = System.nanoTime();
            if (now - lastWarning > 60_000_000_000L) {
                lastWarning = now;
                log.warn("trace não gravado em {}: {}", dir, e.getMessage());
            }
        }
    }

    private synchronized void append(Map<String, Object> line) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
        }
        Path file = today();
        boolean fresh = !Files.exists(file);
        Files.writeString(file, json.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            restrict(file, "rw-------");
        }
    }

    private static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // Sistema de arquivos sem POSIX; o núcleo roda no WSL.
        }
    }

    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
        }
    }
}
