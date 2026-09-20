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
package zordon.core.defense;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O retrato do que está instalado e configurado (SPEC-026, Anel 3). Mudou com o
 * Zordon rodando? Isso é adulteração. Atualizar reinicia o serviço, então instalar
 * nunca dispara.
 */
@Spec("SPEC-026")
public final class IntegrityWatch implements AutoCloseable {

    static final Duration EVERY = Duration.ofMinutes(15);
    /** Um jar grande não precisa ser lido inteiro para mudar de retrato. */
    static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(IntegrityWatch.class);

    /** O que mudou: {@code integrity.self} ou {@code integrity.config}. */
    public interface Listener {
        void changed(String kind, String path, String reason);
    }

    private final List<Path> installed;
    private final List<Path> configured;
    private final Listener listener;
    private final Map<String, String> snapshot = new LinkedHashMap<>();
    private ScheduledExecutorService timer;
    private String error;

    public IntegrityWatch(List<Path> installed, List<Path> configured, Listener listener) {
        this.installed = List.copyOf(installed);
        this.configured = List.copyOf(configured);
        this.listener = listener;
    }

    /** As pastas do Zordon instalado, a partir de onde este código está rodando. */
    public static IntegrityWatch of(Path home, Listener listener) {
        List<Path> installed = new java.util.ArrayList<>();
        try {
            Path jar = Path.of(IntegrityWatch.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(jar)) {
                installed.add(jar.getParent());   // .../zordon/lib
                if (jar.getParent() != null && jar.getParent().getParent() != null) {
                    installed.add(jar.getParent().getParent().resolve("bin"));
                }
            }
        } catch (RuntimeException | java.net.URISyntaxException e) {
            log.debug("origem do código não resolvida: {}", e.getMessage());
        }
        return new IntegrityWatch(installed, List.of(home.resolve("config.toml"), home.resolve("agents"),
                home.resolve("automations")), listener);
    }

    public void start() {
        snapshot.putAll(hashes());
        log.info("integridade: {} arquivos no retrato", snapshot.size());
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("zordon-integridade").factory());
        timer.scheduleWithFixedDelay(this::check, EVERY.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Uma conferência. Pública para o teste rodar sem esperar 15 min. */
    public synchronized List<String> check() {
        List<String> changes = new java.util.ArrayList<>();
        Map<String, String> now = hashes();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String current = now.get(entry.getKey());
            if (current == null) {
                changes.add(entry.getKey());
                report(entry.getKey(), "o arquivo sumiu depois do retrato");
            } else if (!current.equals(entry.getValue())) {
                changes.add(entry.getKey());
                report(entry.getKey(), "o conteúdo mudou depois do retrato");
            }
        }
        for (String path : now.keySet()) {
            if (!snapshot.containsKey(path)) {
                changes.add(path);
                report(path, "arquivo novo depois do retrato");
            }
        }
        snapshot.clear();
        snapshot.putAll(now);   // o retrato passa a ser o estado atual: um aviso por mudança
        return changes;
    }

    /** O fluxo aprovou uma mudança (uma automação nova): o retrato acompanha, sem alarme. */
    public synchronized void accept(Path path) {
        hash(path).ifPresent(hash -> snapshot.put(path.toString(), hash));
    }

    private void report(String path, String reason) {
        boolean self = installed.stream().anyMatch(dir -> path.startsWith(dir.toString()));
        log.warn("integridade: {} — {}", path, reason);
        listener.changed(self ? "integrity.self" : "integrity.config", path, reason);
    }

    private Map<String, String> hashes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path root : Stream.concat(installed.stream(), configured.stream()).toList()) {
            if (Files.isRegularFile(root)) {
                hash(root).ifPresent(hash -> out.put(root.toString(), hash));
                continue;
            }
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.list(root)) {
                files.filter(Files::isRegularFile).sorted()
                        .forEach(file -> hash(file).ifPresent(hash -> out.put(file.toString(), hash)));
            } catch (IOException e) {
                error = root + ": " + e.getMessage();
                log.debug("integridade: {} ilegível", root);
            }
        }
        return out;
    }

    private java.util.Optional<String> hash(Path file) {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            long total = 0;
            while (total < MAX_BYTES && (read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                total += read;
            }
            digest.update(Long.toString(Files.size(file)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            error = file + ": " + e.getMessage();
            return java.util.Optional.empty();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", snapshot.size());
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
    }
}
