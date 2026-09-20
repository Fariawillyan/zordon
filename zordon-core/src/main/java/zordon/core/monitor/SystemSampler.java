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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * Métricas do WSL lidas de {@code /proc}, com frequência que se adapta (SPEC-024):
 * 1 Hz enquanto alguém precisa, 0,2 Hz no resto do tempo. Ler {@code /proc} custa
 * microssegundos; o que custaria é medir sem ninguém olhar.
 */
@Spec("SPEC-024")
public final class SystemSampler implements AutoCloseable {

    /** Uma amostra. Métrica que não pôde ser lida vem como {@code -1}. */
    public record Snapshot(double cpu, long memUsedKb, long memTotalKb, double diskUsedGb, double diskTotalGb,
            double netRxKbps, double netTxKbps, double load, Instant at) {

        public static final Snapshot EMPTY = new Snapshot(-1, -1, -1, -1, -1, -1, -1, -1, Instant.EPOCH);

        public Map<String, Object> wire(double rateHz) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cpu", round(cpu));
            out.put("memUsedMb", memUsedKb < 0 ? -1 : memUsedKb / 1024);
            out.put("memTotalMb", memTotalKb < 0 ? -1 : memTotalKb / 1024);
            out.put("memPercent", memTotalKb <= 0 ? -1 : round(100.0 * memUsedKb / memTotalKb));
            out.put("diskUsedGb", round(diskUsedGb));
            out.put("diskTotalGb", round(diskTotalGb));
            out.put("diskPercent", diskTotalGb <= 0 ? -1 : round(100.0 * diskUsedGb / diskTotalGb));
            out.put("netRxKbps", round(netRxKbps));
            out.put("netTxKbps", round(netTxKbps));
            out.put("load", round(load));
            out.put("sampledAt", at.toString());
            out.put("rateHz", rateHz);
            return out;
        }

        /** O valor de uma métrica pelo nome usado nas automações de condição (SPEC-025). */
        public double metric(String name) {
            return switch (name) {
                case "cpu" -> cpu;
                case "mem" -> memTotalKb <= 0 ? -1 : 100.0 * memUsedKb / memTotalKb;
                case "disk" -> diskTotalGb <= 0 ? -1 : 100.0 * diskUsedGb / diskTotalGb;
                case "load" -> load;
                case "netRx" -> netRxKbps;
                case "netTx" -> netTxKbps;
                default -> throw new IllegalArgumentException("métrica desconhecida: " + name);
            };
        }

        private static double round(double value) {
            return value < 0 ? -1 : Math.round(value * 10) / 10.0;
        }
    }

    public static final List<String> METRICS = List.of("cpu", "mem", "disk", "load", "netRx", "netTx");
    static final Duration FAST = Duration.ofSeconds(1);
    static final Duration SLOW = Duration.ofSeconds(5);
    static final Duration DEMAND_WINDOW = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(SystemSampler.class);

    private final Path proc;
    private final Path disk;
    private final Clock clock;
    private final BooleanSupplier conditions;
    private volatile Snapshot latest = Snapshot.EMPTY;
    private volatile Instant demandedAt = Instant.EPOCH;
    private volatile boolean fast;
    private long[] lastCpu;
    private long[] lastNet;
    private Instant lastNetAt;
    private ScheduledExecutorService timer;

    /**
     * @param proc a raiz do {@code /proc} (um diretório falso nos testes)
     * @param conditions há automação de condição ativa: pede 1 Hz
     */
    public SystemSampler(Path proc, Path disk, Clock clock, BooleanSupplier conditions) {
        this.proc = proc;
        this.disk = disk;
        this.clock = clock;
        this.conditions = conditions;
    }

    public static SystemSampler system(BooleanSupplier conditions) {
        return new SystemSampler(Path.of("/proc"), Path.of("/"), Clock.systemUTC(), conditions);
    }

    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("zordon-sampler").factory());
        sample();
        schedule(SLOW);
    }

    private void schedule(Duration every) {
        timer.schedule(() -> {
            sample();
            boolean wantFast = needsFast();
            if (wantFast != fast) {
                fast = wantFast;
                log.info("amostragem do sistema a {}", wantFast ? "1 Hz" : "0,2 Hz");
            }
            schedule(wantFast ? FAST : SLOW);
        }, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean needsFast() {
        return conditions.getAsBoolean() || Duration.between(demandedAt, clock.instant()).compareTo(DEMAND_WINDOW) < 0;
    }

    /** Alguém olhou as métricas: 1 Hz pelos próximos 60 s. */
    public void demand() {
        demandedAt = clock.instant();
    }

    public double rateHz() {
        return fast ? 1.0 : 0.2;
    }

    public Snapshot latest() {
        return latest;
    }

    /** Uma leitura. Público para os testes rodarem sem esperar o relógio. */
    public synchronized Snapshot sample() {
        Instant now = clock.instant();
        double cpu = -1;
        long[] cpuNow = cpuTicks();
        if (cpuNow != null && lastCpu != null) {
            long total = cpuNow[0] - lastCpu[0];
            long idle = cpuNow[1] - lastCpu[1];
            cpu = total <= 0 ? 0 : 100.0 * (total - idle) / total;
        }
        if (cpuNow != null) {
            lastCpu = cpuNow;
        }
        long memTotal = -1;
        long memAvailable = -1;
        try {
            for (String line : Files.readAllLines(proc.resolve("meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    memTotal = kilobytes(line);
                } else if (line.startsWith("MemAvailable:")) {
                    memAvailable = kilobytes(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("meminfo ilegível: {}", e.getMessage());
        }
        double load = -1;
        try {
            load = Double.parseDouble(Files.readString(proc.resolve("loadavg")).split(" ")[0]);
        } catch (IOException | RuntimeException e) {
            log.debug("loadavg ilegível: {}", e.getMessage());
        }
        double diskUsed = -1;
        double diskTotal = -1;
        try {
            var store = Files.getFileStore(disk);
            diskTotal = store.getTotalSpace() / 1e9;
            diskUsed = (store.getTotalSpace() - store.getUsableSpace()) / 1e9;
        } catch (IOException e) {
            log.debug("disco ilegível: {}", e.getMessage());
        }
        double rx = -1;
        double tx = -1;
        long[] netNow = netBytes();
        if (netNow != null && lastNet != null && lastNetAt != null) {
            double seconds = Math.max(0.001, Duration.between(lastNetAt, now).toMillis() / 1000.0);
            rx = Math.max(0, (netNow[0] - lastNet[0]) / 1024.0 / seconds);
            tx = Math.max(0, (netNow[1] - lastNet[1]) / 1024.0 / seconds);
        }
        if (netNow != null) {
            lastNet = netNow;
            lastNetAt = now;
        }
        latest = new Snapshot(cpu, memTotal < 0 || memAvailable < 0 ? -1 : memTotal - memAvailable, memTotal,
                diskUsed, diskTotal, rx, tx, load, now);
        return latest;
    }

    /** {total, ocioso} somados da linha {@code cpu} de {@code /proc/stat}. */
    private long[] cpuTicks() {
        try {
            for (String line : Files.readAllLines(proc.resolve("stat"))) {
                if (line.startsWith("cpu ")) {
                    String[] fields = line.trim().split("\\s+");
                    long total = 0;
                    for (int i = 1; i < fields.length; i++) {
                        total += Long.parseLong(fields[i]);
                    }
                    long idle = Long.parseLong(fields[4]) + (fields.length > 5 ? Long.parseLong(fields[5]) : 0);
                    return new long[] {total, idle};
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("stat ilegível: {}", e.getMessage());
        }
        return null;
    }

    /** {recebidos, enviados} somados das interfaces, sem a {@code lo}. */
    private long[] netBytes() {
        try {
            long rx = 0;
            long tx = 0;
            for (String line : Files.readAllLines(proc.resolve("net/dev"))) {
                int colon = line.indexOf(':');
                if (colon < 0 || line.substring(0, colon).trim().equals("lo")) {
                    continue;
                }
                String[] fields = line.substring(colon + 1).trim().split("\\s+");
                rx += Long.parseLong(fields[0]);
                tx += Long.parseLong(fields[8]);
            }
            return new long[] {rx, tx};
        } catch (IOException | RuntimeException e) {
            log.debug("net/dev ilegível: {}", e.getMessage());
            return null;
        }
    }

    private static long kilobytes(String line) {
        return Long.parseLong(line.replaceAll("[^0-9]", ""));
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }
}
