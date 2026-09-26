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
package zordon.core.monitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.security.AuditLog;
import zordon.security.Gatekeeper;
import zordon.security.LiveProcess;
import zordon.security.ProcessRunner;

/**
 * Eventos de container por push: {@code docker events} como processo longo, pelo
 * caminho auditado (SPEC-024). Nada de consultar a cada N segundos: o Docker avisa.
 */
@Spec("SPEC-024")
public final class DockerEvents implements AutoCloseable {

    /** O que interessa a um assistente; o resto (exec_start, attach…) é ruído. */
    static final Set<String> ACTIONS = Set.of("start", "die", "stop", "restart", "oom", "health_status: healthy",
            "health_status: unhealthy");
    static final List<String> COMMAND = List.of("docker", "events", "--format", "{{json .}}", "--filter",
            "type=container");

    private static final Logger log = LoggerFactory.getLogger(DockerEvents.class);

    private final Gatekeeper gatekeeper;
    private final ProcessRunner runner;
    private final Path workDir;
    private final Consumer<Map<String, Object>> events;
    private final List<Duration> backoff;
    private final AtomicLong received = new AtomicLong();
    private volatile boolean running;
    private volatile String state = "stopped";
    private volatile String reason;
    private volatile LiveProcess live;

    public DockerEvents(Gatekeeper gatekeeper, ProcessRunner runner, Path workDir,
            Consumer<Map<String, Object>> events, List<Duration> backoff) {
        this.gatekeeper = gatekeeper;
        this.runner = runner;
        this.workDir = workDir;
        this.events = events;
        this.backoff = List.copyOf(backoff);
    }

    public static List<Duration> defaultBackoff() {
        return List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    public void start() {
        running = true;
        Thread.ofVirtual().name("zordon-docker-events").start(this::loop);
    }

    private void loop() {
        int attempt = 0;
        while (running) {
            boolean opened = follow();
            if (!running) {
                return;
            }
            if (opened) {
                attempt = 0;   // conectou de verdade: a espera volta ao começo
            }
            Duration wait = backoff.get(Math.min(attempt, backoff.size() - 1));
            attempt++;
            state = "retrying";
            log.info("eventos do Docker: nova tentativa em {} s ({})", wait.toSeconds(), reason);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Uma conexão, até o stream acabar. @return se chegou a abrir */
    private boolean follow() {
        Gatekeeper.Permit.Granted granted;
        try {
            Gatekeeper.Permit permit = DockerEventAuthorization.authorize(gatekeeper, COMMAND);
            if (!(permit instanceof Gatekeeper.Permit.Granted ok)) {
                state = "unavailable";
                reason = permit.decision().reason();
                running = false;   // negado pela política: não adianta insistir
                log.warn("eventos do Docker desligados: {}", reason);
                return false;
            }
            granted = ok;
        } catch (Exception e) {
            reason = e.getMessage();
            return false;
        }
        boolean audited = false;
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            reason = "pasta de trabalho: " + e.getMessage();
        }
        try (LiveProcess process = runner.start(granted, workDir)) {
            live = process;
            granted.complete(new AuditLog.Completion(AuditLog.Status.OK, Duration.ZERO, "stream aberto", null));
            audited = true;
            state = "streaming";
            reason = null;
            log.info("eventos do Docker: acompanhando");
            DockerEventParser.lines(process.stdout(), () -> running, this::handle);
            reason = "o stream do docker terminou";
            return true;
        } catch (Exception e) {
            if (!audited) {
                granted.complete(new AuditLog.Completion(AuditLog.Status.FAILED, Duration.ZERO, null,
                        e.getMessage()));
            }
            reason = e.getMessage();
            return audited;
        } finally {
            live = null;
        }
    }

    /** Uma linha do {@code docker events}. Pacote para os testes. */
    void handle(String line) {
        DockerEventParser.parse(line).ifPresent(payload -> {
            received.incrementAndGet();
            events.accept(payload);
        });
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state);
        if (reason != null) {
            out.put("reason", reason);
        }
        out.put("events", received.get());
        return out;
    }

    @Override
    public void close() {
        running = false;
        LiveProcess current = live;
        if (current != null) {
            current.close();
        }
    }
}
