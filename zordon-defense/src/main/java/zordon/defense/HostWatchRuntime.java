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
package zordon.defense;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Coordinates the host-watch scanners and their lifecycle. */
final class HostWatchRuntime implements AutoCloseable {

    private final HostWatchCredentials credentials;
    private final HostWatchListeners listeners;
    private final HostWatchPersistence persistence;
    private final java.util.function.Consumer<Observation> sink;
    private final Clock clock;
    private ScheduledExecutorService timer;

    HostWatchRuntime(HostWatch.Config config) {
        credentials = new HostWatchCredentials(config);
        listeners = new HostWatchListeners(config);
        clock = config.clock();
        sink = config.sink();
        persistence = new HostWatchPersistence(config, this::persistenceChanged);
    }

    void start() {
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("zordon-anel2").factory());
        timer.scheduleWithFixedDelay(this::credentials, HostWatch.CREDENTIALS_EVERY.toMillis(),
                HostWatch.CREDENTIALS_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::listeners, 1_000, HostWatch.LISTENERS_EVERY.toMillis(),
                TimeUnit.MILLISECONDS);
        persistence.start();
    }

    List<Observation> credentials() { return credentials.scan(); }
    List<Observation> listeners() { return listeners.scan(); }

    void persistenceChanged(Path changed) {
        sink.accept(new Observation("host.persistence", Subject.file(changed.toString()), "file", null, null, null,
                null, null, null, "arquivo de inicialização alterado: " + changed, false,
                Map.of("arquivo", changed.toString()), clock.instant()));
    }

    Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credentialsWatched", credentials.scanCount());
        out.put("listeners", listeners.count());
        out.put("openAlerts", credentials.alertCount());
        String error = credentials.error() != null ? credentials.error()
                : listeners.error() != null ? listeners.error() : persistence.error();
        if (error != null) {
            out.put("error", error);
        }
        return out;
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
        persistence.close();
    }
}
