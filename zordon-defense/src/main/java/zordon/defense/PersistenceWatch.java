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
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Os arquivos de inicialização ({@code .bashrc}, autostart, units do usuário), vigiados pelo inotify. */
final class PersistenceWatch {

    private static final Logger log = LoggerFactory.getLogger(HostWatch.class);

    private final HostWatch.Config config;
    private final Consumer<String> errors;
    private volatile WatchService watcher;

    PersistenceWatch(HostWatch.Config config, Consumer<String> errors) {
        this.config = config;
        this.errors = errors;
    }

    void start() {
        if (!register()) {
            return;
        }
        Thread.ofVirtual().name("zordon-persistencia").start(this::watch);
    }

    /** Um arquivo de persistência mudou. */
    void changed(Path changed) {
        config.sink().accept(new Observation("host.persistence", Subject.file(changed.toString()), "file", null, null,
                null, null, null, null, "arquivo de inicialização alterado: " + changed, false,
                Map.of("arquivo", changed.toString()), config.clock().instant()));
    }

    void close() {
        try {
            if (watcher != null) {
                WatchService current = watcher;
                watcher = null;
                current.close();
            }
        } catch (IOException e) {
            log.debug("watcher: {}", e.getMessage());
        }
    }

    /** Registra os diretórios vigiados. {@code false} quando nem deu para começar. */
    private boolean register() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
            Set<Path> dirs = new LinkedHashSet<>();
            config.persistence().forEach(path -> dirs.add(Files.isDirectory(path) ? path : path.getParent()));
            for (Path dir : dirs) {
                if (dir != null && Files.isDirectory(dir)) {
                    dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
                }
            }
            return true;
        } catch (IOException e) {
            errors.accept("persistência: " + e.getMessage());
            return false;
        }
    }

    private void watch() {
        while (watcher != null) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
                return;
            }
            report(key);
            key.reset();
        }
    }

    private void report(WatchKey key) {
        for (var event : key.pollEvents()) {
            Path dir = (Path) key.watchable();
            Path changed = dir.resolve(String.valueOf(event.context()));
            if (config.persistence().stream().anyMatch(watched -> changed.startsWith(watched) || changed.equals(watched))) {
                changed(changed);
            }
        }
    }
}
