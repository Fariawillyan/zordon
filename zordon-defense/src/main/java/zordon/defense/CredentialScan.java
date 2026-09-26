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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Quem está com uma credencial aberta: os descritores de cada processo em {@code /proc}. */
final class CredentialScan {

    static final Duration EVERY = Duration.ofSeconds(2);

    /** Programas que abrem credencial no dia a dia e não são notícia. */
    static final Set<String> KNOWN = Set.of("ssh", "sshd", "ssh-agent", "ssh-keygen", "ssh-add", "scp", "sftp",
            "git", "git-remote-http", "gpg", "gpg-agent", "gnome-keyring-d", "code", "node", "java", "python3");

    private static final Logger log = LoggerFactory.getLogger(HostWatch.class);

    private final HostWatch.Config config;
    private final Consumer<String> errors;
    private final Set<String> reported = new LinkedHashSet<>();

    CredentialScan(HostWatch.Config config, Consumer<String> errors) {
        this.config = config;
        this.errors = errors;
    }

    synchronized List<Observation> scan() {
        List<Observation> found = new ArrayList<>();
        try (Stream<Path> pids = Files.list(config.proc())) {
            for (Path pid : pids.filter(path -> path.getFileName().toString().matches("\\d+")).toList()) {
                String number = pid.getFileName().toString();
                String command = command(pid);
                if (KNOWN.contains(command)) {
                    continue;
                }
                for (String open : openFiles(pid)) {
                    if (config.credentialPaths().stream().noneMatch(open::contains)) {
                        continue;
                    }
                    String key = number + " " + open;
                    if (!reported.add(key)) {
                        continue;   // já avisado enquanto o descritor segue aberto
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("pid", number);
                    data.put("processo", command);
                    data.put("arquivo", open);
                    Observation observation = new Observation("host.credential-access", Subject.process(number),
                            "process:" + number, null, null, null, null, null, null,
                            "o processo " + command + " (pid " + number + ") está com " + open + " aberto", false,
                            data, config.clock().instant());
                    found.add(observation);
                    config.sink().accept(observation);
                }
            }
        } catch (IOException | RuntimeException e) {
            errors.accept("credenciais: " + e.getMessage());
            log.debug("varredura de credenciais: {}", e.getMessage());
        }
        reported.removeIf(key -> !stillOpen(key));
        return found;
    }

    synchronized int openAlerts() {
        return reported.size();
    }

    private boolean stillOpen(String key) {
        String pid = key.substring(0, key.indexOf(' '));
        String file = key.substring(key.indexOf(' ') + 1);
        return openFiles(config.proc().resolve(pid)).contains(file);
    }

    private List<String> openFiles(Path pid) {
        try (Stream<Path> fds = Files.list(pid.resolve("fd"))) {
            List<String> out = new ArrayList<>();
            for (Path fd : fds.toList()) {
                try {
                    out.add(Files.readSymbolicLink(fd).toString());
                } catch (IOException | UnsupportedOperationException e) {
                    // descritor que já fechou, ou não é link: segue
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();   // processo de outro usuário, ou que já morreu
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
