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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Portas novas em LISTEN, lidas de {@code /proc/net/tcp}. A primeira varredura só monta a linha de base. */
final class ListenerScan {

    static final Duration EVERY = Duration.ofSeconds(5);

    private final HostWatch.Config config;
    private final Consumer<String> errors;
    private final Set<String> listeners = new LinkedHashSet<>();
    private boolean firstScan = true;

    ListenerScan(HostWatch.Config config, Consumer<String> errors) {
        this.config = config;
        this.errors = errors;
    }

    synchronized List<Observation> scan() {
        Set<String> current = new LinkedHashSet<>();
        for (String file : List.of("net/tcp", "net/tcp6")) {
            try {
                for (String line : Files.readAllLines(config.proc().resolve(file), StandardCharsets.UTF_8)) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length < 4 || !"0A".equals(fields[3])) {
                        continue;   // 0A = LISTEN
                    }
                    String local = fields[1];
                    current.add(String.valueOf(Integer.parseInt(local.substring(local.indexOf(':') + 1), 16)));
                }
            } catch (IOException | RuntimeException e) {
                errors.accept("portas: " + e.getMessage());
            }
        }
        List<Observation> found = new ArrayList<>();
        if (firstScan) {
            firstScan = false;
            listeners.addAll(current);
            return found;
        }
        for (String port : current) {
            if (listeners.add(port)) {
                Observation observation = new Observation("host.new-listener", new Subject("port", port),
                        "port:" + port, null, null, null, null, null, null,
                        "uma porta nova apareceu escutando: " + port, false, Map.of("porta", port),
                        config.clock().instant());
                found.add(observation);
                config.sink().accept(observation);
            }
        }
        listeners.retainAll(current);
        return found;
    }

    synchronized int count() {
        return listeners.size();
    }
}
