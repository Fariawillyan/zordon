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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O Anel 2 possível de dentro do WSL, sem root (SPEC-027): quem está com uma
 * credencial aberta, o que mexeu em arquivo de persistência e que porta nova
 * apareceu escutando.
 *
 * <p>Limite honesto: sem {@code fanotify} ou {@code auditd} (que precisam de root),
 * uma leitura de milissegundos pode passar entre duas varreduras. O que se pega é o
 * processo que **mantém** o arquivo aberto.
 */
@Spec("SPEC-027")
public final class HostWatch implements AutoCloseable {

    static final Duration CREDENTIALS_EVERY = Duration.ofSeconds(2);
    static final Duration LISTENERS_EVERY = Duration.ofSeconds(5);

    /** Programas que abrem credencial no dia a dia e não são notícia. */
    static final Set<String> KNOWN = Set.of("ssh", "sshd", "ssh-agent", "ssh-keygen", "ssh-add", "scp", "sftp",
            "git", "git-remote-http", "gpg", "gpg-agent", "gnome-keyring-d", "code", "node", "java", "python3");

    private static final Logger log = LoggerFactory.getLogger(HostWatch.class);

    private final Path proc;
    private final List<String> credentialPaths;
    private final List<Path> persistence;
    private final Clock clock;
    private final Consumer<Observation> sink;
    private final Set<String> reported = new LinkedHashSet<>();
    private final Set<String> listeners = new LinkedHashSet<>();
    private ScheduledExecutorService timer;
    private java.nio.file.WatchService watcher;
    private String error;
    private boolean firstListenerScan = true;

    public HostWatch(Path proc, Path home, Clock clock, Consumer<Observation> sink) {
        this(proc, List.of(home + "/.ssh/", home + "/.aws/", home + "/.gnupg/", home + "/.zordon/config.toml", ".env"),
                List.of(home.resolve(".bashrc"), home.resolve(".profile"), home.resolve(".config/systemd/user"),
                        home.resolve(".config/autostart")), clock, sink);
    }

    public HostWatch(Path proc, List<String> credentialPaths, List<Path> persistence, Clock clock,
            Consumer<Observation> sink) {
        this.proc = proc;
        this.credentialPaths = List.copyOf(credentialPaths);
        this.persistence = List.copyOf(persistence);
        this.clock = clock;
        this.sink = sink;
    }

    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("zordon-anel2").factory());
        timer.scheduleWithFixedDelay(this::credentials, CREDENTIALS_EVERY.toMillis(), CREDENTIALS_EVERY.toMillis(),
                TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::newListeners, 1_000, LISTENERS_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        startPersistence();
    }

    /** Uma varredura de descritores abertos. Pública para o teste rodar sem esperar. */
    public synchronized List<Observation> credentials() {
        List<Observation> found = new ArrayList<>();
        try (Stream<Path> pids = Files.list(proc)) {
            for (Path pid : pids.filter(path -> path.getFileName().toString().matches("\\d+")).toList()) {
                String number = pid.getFileName().toString();
                String command = command(pid);
                if (KNOWN.contains(command)) {
                    continue;
                }
                for (String open : openFiles(pid)) {
                    if (credentialPaths.stream().noneMatch(open::contains)) {
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
                            data, clock.instant());
                    found.add(observation);
                    sink.accept(observation);
                }
            }
        } catch (IOException | RuntimeException e) {
            error = "credenciais: " + e.getMessage();
            log.debug("varredura de credenciais: {}", e.getMessage());
        }
        reported.removeIf(key -> !stillOpen(key));
        return found;
    }

    private boolean stillOpen(String key) {
        String pid = key.substring(0, key.indexOf(' '));
        String file = key.substring(key.indexOf(' ') + 1);
        return openFiles(proc.resolve(pid)).contains(file);
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

    /** Portas novas em LISTEN. A primeira varredura só monta a linha de base. */
    public synchronized List<Observation> newListeners() {
        Set<String> current = new LinkedHashSet<>();
        for (String file : List.of("net/tcp", "net/tcp6")) {
            try {
                for (String line : Files.readAllLines(proc.resolve(file), StandardCharsets.UTF_8)) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length < 4 || !"0A".equals(fields[3])) {
                        continue;   // 0A = LISTEN
                    }
                    String local = fields[1];
                    current.add(String.valueOf(Integer.parseInt(local.substring(local.indexOf(':') + 1), 16)));
                }
            } catch (IOException | RuntimeException e) {
                error = "portas: " + e.getMessage();
            }
        }
        List<Observation> found = new ArrayList<>();
        if (firstListenerScan) {
            firstListenerScan = false;
            listeners.addAll(current);
            return found;
        }
        for (String port : current) {
            if (listeners.add(port)) {
                Observation observation = new Observation("host.new-listener", new Subject("port", port),
                        "port:" + port, null, null, null, null, null, null,
                        "uma porta nova apareceu escutando: " + port, false, Map.of("porta", port), clock.instant());
                found.add(observation);
                sink.accept(observation);
            }
        }
        listeners.retainAll(current);
        return found;
    }

    private void startPersistence() {
        if (!registerPersistence()) {
            return;
        }
        Thread.ofVirtual().name("zordon-persistencia").start(this::watchPersistence);
    }

    /** Registra os diretórios vigiados. {@code false} quando nem deu para começar. */
    private boolean registerPersistence() {
        try {
            watcher = java.nio.file.FileSystems.getDefault().newWatchService();
            Set<Path> dirs = new LinkedHashSet<>();
            persistence.forEach(path -> dirs.add(Files.isDirectory(path) ? path : path.getParent()));
            for (Path dir : dirs) {
                if (dir != null && Files.isDirectory(dir)) {
                    dir.register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                            java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
                }
            }
            return true;
        } catch (IOException e) {
            error = "persistência: " + e.getMessage();
            return false;
        }
    }

    private void watchPersistence() {
        while (watcher != null) {
            java.nio.file.WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
                return;
            }
            reportChanges(key);
            key.reset();
        }
    }

    private void reportChanges(java.nio.file.WatchKey key) {
        for (var event : key.pollEvents()) {
            Path dir = (Path) key.watchable();
            Path changed = dir.resolve(String.valueOf(event.context()));
            if (persistence.stream().anyMatch(watched -> changed.startsWith(watched) || changed.equals(watched))) {
                persistenceChanged(changed);
            }
        }
    }

    /** Um arquivo de persistência mudou. Pública para o teste não depender do inotify. */
    public void persistenceChanged(Path changed) {
        sink.accept(new Observation("host.persistence", Subject.file(changed.toString()), "file", null, null, null,
                null, null, null, "arquivo de inicialização alterado: " + changed, false,
                Map.of("arquivo", changed.toString()), clock.instant()));
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credentialsWatched", credentialPaths.size());
        out.put("listeners", listeners.size());
        out.put("openAlerts", reported.size());
        if (error != null) {
            out.put("error", error);
        }
        return out;
    }

    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
        try {
            if (watcher != null) {
                java.nio.file.WatchService current = watcher;
                watcher = null;
                current.close();
            }
        } catch (IOException e) {
            log.debug("watcher: {}", e.getMessage());
        }
    }
}
