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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Scans proc descriptors for access to protected credentials. */
final class HostWatchCredentials {

    private final Path proc;
    private final List<String> paths;
    private final Clock clock;
    private final Consumer<Observation> sink;
    private final Set<String> reported = new LinkedHashSet<>();
    private String error;

    HostWatchCredentials(HostWatch.Config config) {
        proc = config.proc();
        paths = List.copyOf(config.credentialPaths());
        clock = config.clock();
        sink = config.sink();
    }

    List<Observation> scan() {
        List<Observation> found = new ArrayList<>();
        try (Stream<Path> pids = Files.list(proc)) {
            for (Path pid : pids.filter(path -> path.getFileName().toString().matches("\\d+")).toList()) {
                String number = pid.getFileName().toString();
                String command = command(pid);
                if (HostWatch.KNOWN.contains(command)) {
                    continue;
                }
                for (String open : openFiles(pid)) {
                    if (paths.stream().noneMatch(open::contains)) {
                        continue;
                    }
                    String key = number + " " + open;
                    if (!reported.add(key)) {
                        continue;
                    }
                    Observation observation = new Observation("host.credential-access",
                            new Subject("process", number), "process:" + number, null, null, null, null,
                            null, null, "o processo " + command + " (pid " + number + ") está com " + open
                                    + " aberto", false, java.util.Map.of("pid", number, "processo", command,
                                            "arquivo", open), clock.instant());
                    found.add(observation);
                    sink.accept(observation);
                }
            }
        } catch (IOException | RuntimeException e) {
            error = "credenciais: " + e.getMessage();
        }
        reported.removeIf(key -> !stillOpen(key));
        return found;
    }

    int alertCount() { return reported.size(); }
    int scanCount() { return paths.size(); }
    String error() { return error; }

    private boolean stillOpen(String key) {
        int split = key.indexOf(' ');
        return openFiles(proc.resolve(key.substring(0, split))).contains(key.substring(split + 1));
    }

    private List<String> openFiles(Path pid) {
        try (Stream<Path> fds = Files.list(pid.resolve("fd"))) {
            List<String> out = new ArrayList<>();
            for (Path fd : fds.toList()) {
                try {
                    out.add(Files.readSymbolicLink(fd).toString());
                } catch (IOException | UnsupportedOperationException e) {
                    // O descritor fechou durante a varredura.
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private String command(Path pid) {
        try {
            return Files.readString(pid.resolve("comm")).strip();
        } catch (IOException e) {
            return "?";
        }
    }
}
