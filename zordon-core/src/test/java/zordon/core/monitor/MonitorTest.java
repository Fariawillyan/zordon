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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.security.CommandValidator;
import zordon.security.DefaultPermissionEngine;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngine;
import zordon.security.ProcessRunner;
import zordon.security.Redactor;
import zordon.security.SqliteAuditLog;

/** O monitor do sistema (SPEC-024). */
class MonitorTest {

    @TempDir
    Path home;

    static final class Manual extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-19T15:00:00Z"));

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private void proc(long user, long idle, long rx, long tx) throws Exception {
        Path proc = home.resolve("proc");
        Files.createDirectories(proc.resolve("net"));
        Files.writeString(proc.resolve("stat"), "cpu  " + user + " 0 0 " + idle + " 0 0 0 0 0 0\ncpu0 1 2 3 4\n");
        Files.writeString(proc.resolve("meminfo"), "MemTotal:       16000000 kB\nMemFree: 1 kB\nMemAvailable:    4000000 kB\n");
        Files.writeString(proc.resolve("loadavg"), "0.52 0.40 0.30 1/200 999\n");
        Files.writeString(proc.resolve("net/dev"), """
                Inter-|   Receive                                                |  Transmit
                 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets
                    lo: 999999 1 0 0 0 0 0 0 999999 1 0 0 0 0 0 0
                  eth0: %d 10 0 0 0 0 0 0 %d 10 0 0 0 0 0 0
                """.formatted(rx, tx));
    }

    @AcceptanceCriteria("SPEC-024/CA-1")
    @Test
    void amostraCpuMemoriaERedeEAdaptaAFrequencia() throws Exception {
        Manual clock = new Manual();
        AtomicBoolean condition = new AtomicBoolean(false);
        proc(1000, 9000, 0, 0);
        SystemSampler sampler = new SystemSampler(home.resolve("proc"), home, clock, condition::get);

        SystemSampler.Snapshot first = sampler.sample();
        assertThat(first.cpu()).as("uma leitura só não dá CPU").isEqualTo(-1);
        proc(1300, 9700, 1024 * 100, 1024 * 50);
        clock.now.set(clock.instant().plusSeconds(1));
        SystemSampler.Snapshot second = sampler.sample();
        assertThat(second.cpu()).isEqualTo(30.0);
        assertThat(second.memUsedKb()).isEqualTo(12_000_000);
        assertThat(second.metric("mem")).isEqualTo(75.0);
        assertThat(second.load()).isEqualTo(0.52);
        assertThat(second.netRxKbps()).isEqualTo(100.0);
        assertThat(second.netTxKbps()).isEqualTo(50.0);
        assertThat(second.wire(sampler.rateHz())).containsEntry("memPercent", 75.0).containsEntry("rateHz", 0.2);

        assertThat(sampler.needsFast()).isFalse();
        sampler.demand();
        assertThat(sampler.needsFast()).as("consulta recente").isTrue();
        clock.now.set(clock.instant().plusSeconds(61));
        assertThat(sampler.needsFast()).isFalse();
        condition.set(true);
        assertThat(sampler.needsFast()).as("condição ativa").isTrue();
    }

    private SqliteAuditLog audit;

    private DockerEvents docker(Path script, List<Map<String, Object>> sink) throws Exception {
        audit = new SqliteAuditLog(home.resolve("audit.db"), new Redactor(), Clock.systemUTC());
        CommandValidator validator = new CommandValidator(Map.of("docker", script.toString()));
        PermissionEngine.Approver none = (action, actor, risk, ttl, perAction) ->
                CompletableFuture.completedFuture(PermissionEngine.Approval.DENY);
        Gatekeeper gatekeeper = new Gatekeeper(new DefaultPermissionEngine(
                PathPolicy.defaults(home.toString(), List.of("~/dev"), List.of("~")), validator, new Redactor(),
                () -> none), audit);
        return new DockerEvents(gatekeeper, new ProcessRunner(validator), home.resolve("work"), sink::add,
                List.of(Duration.ofMillis(100)));
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("não aconteceu: " + what);
            }
            Thread.sleep(20);
        }
    }

    @AcceptanceCriteria("SPEC-024/CA-2")
    @Test
    void containerQueMorreViraEventoPeloCaminhoAuditado() throws Exception {
        Path script = home.resolve("docker");
        Files.writeString(script, """
                #!/bin/sh
                echo "$@" > "%s"
                echo '{"status":"die","id":"abc","from":"minha/api:1","Type":"container","Action":"die","Actor":{"ID":"abc","Attributes":{"exitCode":"137","image":"minha/api:1","name":"api"}},"time":1758294000}'
                echo '{"Type":"container","Action":"exec_start: sh","Actor":{"Attributes":{"name":"api"}}}'
                echo 'não é json'
                echo '{"Type":"container","Action":"health_status: unhealthy","Actor":{"Attributes":{"image":"db:16","name":"db"}},"time":1758294001}'
                sleep 30
                """.formatted(home.resolve("args.txt")));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        try (DockerEvents stream = docker(script, events)) {
            stream.start();
            await(() -> events.size() == 2, "dois eventos");

            assertThat(events.getFirst()).containsEntry("container", "api").containsEntry("action", "die")
                    .containsEntry("exitCode", 137).containsEntry("image", "minha/api:1")
                    .containsEntry("at", "2025-09-19T15:00:00Z");
            assertThat(events.get(1)).containsEntry("container", "db").containsEntry("action", "unhealthy");
            assertThat(stream.status()).containsEntry("state", "streaming").containsEntry("events", 2L);
            assertThat(Files.readString(home.resolve("args.txt")).strip())
                    .isEqualTo("events --format {{json .}} --filter type=container");
        }
        List<String> tools = new ArrayList<>();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("audit.db"));
                var rows = db.createStatement().executeQuery("SELECT tool FROM audit")) {
            while (rows.next()) {
                tools.add(rows.getString(1));
            }
        }
        assertThat(tools).contains("monitor.docker");
        audit.close();
    }

    @AcceptanceCriteria("SPEC-024/CA-3")
    @Test
    void streamQueCaiVoltaComEspera() throws Exception {
        Path script = home.resolve("docker");
        Files.writeString(script, """
                #!/bin/sh
                echo '{"Type":"container","Action":"start","Actor":{"Attributes":{"name":"api"}}}'
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        try (DockerEvents stream = docker(script, events)) {
            stream.start();
            await(() -> events.size() >= 3, "três conexões");
            assertThat(events).allSatisfy(event -> assertThat(event).containsEntry("action", "start"));
        }
        audit.close();
    }
}
