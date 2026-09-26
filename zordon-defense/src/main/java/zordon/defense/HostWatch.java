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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import zordon.api.trace.Spec;

/** The small public façade for the three unprivileged host-watch scanners. */
@Spec("SPEC-027")
public final class HostWatch implements AutoCloseable {

    public record Config(Path proc, List<String> credentialPaths, List<Path> persistence, Clock clock,
            Consumer<Observation> sink) {}

    static final Duration CREDENTIALS_EVERY = Duration.ofSeconds(2);
    static final Duration LISTENERS_EVERY = Duration.ofSeconds(5);
    static final Set<String> KNOWN = Set.of("ssh", "sshd", "ssh-agent", "ssh-keygen", "ssh-add", "scp", "sftp",
            "git", "git-remote-http", "gpg", "gpg-agent", "gnome-keyring-d", "code", "node", "java", "python3");

    private final HostWatchRuntime runtime;

    public HostWatch(Path proc, Path home, Clock clock, Consumer<Observation> sink) {
        this(new Config(proc, List.of(home + "/.ssh/", home + "/.aws/", home + "/.gnupg/",
                home + "/.zordon/config.toml", ".env"),
                List.of(home.resolve(".bashrc"), home.resolve(".profile"), home.resolve(".config/systemd/user"),
                        home.resolve(".config/autostart")), clock, sink));
    }

    public HostWatch(Config config) { runtime = new HostWatchRuntime(config); }
    public void start() { runtime.start(); }
    public List<Observation> credentials() { return runtime.credentials(); }
    public List<Observation> newListeners() { return runtime.listeners(); }
    public void persistenceChanged(Path changed) { runtime.persistenceChanged(changed); }
    public Map<String, Object> status() { return runtime.status(); }
    static String lower(String text) { return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT); }
    @Override public void close() { runtime.close(); }
}
