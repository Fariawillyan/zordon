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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Tracks newly listening TCP ports. */
final class HostWatchListeners {

    private final Path proc;
    private final Clock clock;
    private final Consumer<Observation> sink;
    private final Set<String> listeners = new LinkedHashSet<>();
    private String error;
    private boolean first = true;

    HostWatchListeners(HostWatch.Config config) {
        proc = config.proc();
        clock = config.clock();
        sink = config.sink();
    }

    List<Observation> scan() {
        Set<String> current = new LinkedHashSet<>();
        for (String file : List.of("net/tcp", "net/tcp6")) {
            try {
                for (String line : Files.readAllLines(proc.resolve(file), StandardCharsets.UTF_8)) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 4 && "0A".equals(fields[3])) {
                        String local = fields[1];
                        current.add(String.valueOf(Integer.parseInt(local.substring(local.indexOf(':') + 1), 16)));
                    }
                }
            } catch (IOException | RuntimeException e) {
                error = "portas: " + e.getMessage();
            }
        }
        List<Observation> found = new ArrayList<>();
        if (first) {
            first = false;
            listeners.addAll(current);
            return found;
        }
        for (String port : current) {
            if (listeners.add(port)) {
                Observation observation = new Observation("host.new-listener",
                        new Subject("port", port), "port:" + port, null, null, null, null, null, null,
                        "uma porta nova apareceu escutando: " + port, false, java.util.Map.of("porta", port),
                        clock.instant());
                found.add(observation);
                sink.accept(observation);
            }
        }
        listeners.retainAll(current);
        return found;
    }

    int count() { return listeners.size(); }
    String error() { return error; }
}
