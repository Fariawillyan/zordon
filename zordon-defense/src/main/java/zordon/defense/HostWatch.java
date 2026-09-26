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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import zordon.api.trace.Spec;

/**
 * O Anel 2 possível de dentro do WSL, sem root (SPEC-027): quem está com uma
 * credencial aberta, o que mexeu em arquivo de persistência e que porta nova
 * apareceu escutando.
 *
 * <p>Limite honesto: sem {@code fanotify} ou {@code auditd} (que precisam de root),
 * uma leitura de milissegundos pode passar entre duas varreduras. O que se pega é o
 * processo que **mantém** o arquivo aberto.
 *
 * <p>Cada vigia fica numa classe: {@link CredentialScan}, {@link ListenerScan} e
 * {@link PersistenceWatch}.
 */
@Spec("SPEC-027")
public final class HostWatch implements AutoCloseable {

    /** O que vigiar e para onde mandar o que for visto. */
    public record Config(Path proc, List<String> credentialPaths, List<Path> persistence, Clock clock,
            Consumer<Observation> sink) {

        public Config {
            credentialPaths = List.copyOf(credentialPaths);
            persistence = List.copyOf(persistence);
        }
    }

    private final int credentialsWatched;
    private final CredentialScan credentials;
    private final ListenerScan listeners;
    private final PersistenceWatch persistence;
    private ScheduledExecutorService timer;
    /** O último erro de qualquer vigia, para o diagnóstico. */
    private volatile String error;

    public HostWatch(Path proc, Path home, Clock clock, Consumer<Observation> sink) {
        this(new Config(proc,
                List.of(home + "/.ssh/", home + "/.aws/", home + "/.gnupg/", home + "/.zordon/config.toml", ".env"),
                List.of(home.resolve(".bashrc"), home.resolve(".profile"), home.resolve(".config/systemd/user"),
                        home.resolve(".config/autostart")), clock, sink));
    }

    public HostWatch(Config config) {
        this.credentialsWatched = config.credentialPaths().size();
        this.credentials = new CredentialScan(config, failure -> error = failure);
        this.listeners = new ListenerScan(config, failure -> error = failure);
        this.persistence = new PersistenceWatch(config, failure -> error = failure);
    }

    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("zordon-anel2").factory());
        timer.scheduleWithFixedDelay(this::credentials, CredentialScan.EVERY.toMillis(),
                CredentialScan.EVERY.toMillis(), TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::newListeners, 1_000, ListenerScan.EVERY.toMillis(), TimeUnit.MILLISECONDS);
        persistence.start();
    }

    /** Uma varredura de descritores abertos. Pública para o teste rodar sem esperar. */
    public List<Observation> credentials() {
        return credentials.scan();
    }

    /** Portas novas em LISTEN. A primeira varredura só monta a linha de base. */
    public List<Observation> newListeners() {
        return listeners.scan();
    }

    /** Um arquivo de persistência mudou. Pública para o teste não depender do inotify. */
    public void persistenceChanged(Path changed) {
        persistence.changed(changed);
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credentialsWatched", credentialsWatched);
        out.put("listeners", listeners.count());
        out.put("openAlerts", credentials.openAlerts());
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
        persistence.close();
    }
}
