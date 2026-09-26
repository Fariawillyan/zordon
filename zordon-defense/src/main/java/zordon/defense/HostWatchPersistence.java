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
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Watches configured persistence paths without coupling the polling scanners to NIO events. */
final class HostWatchPersistence implements AutoCloseable {

    private final List<Path> paths;
    private final Consumer<Path> changed;
    private WatchService watcher;
    private String error;

    HostWatchPersistence(HostWatch.Config config, Consumer<Path> changed) {
        paths = List.copyOf(config.persistence());
        this.changed = changed;
    }

    void start() {
        if (!register()) {
            return;
        }
        Thread.ofVirtual().name("zordon-persistencia").start(this::watch);
    }

    String error() { return error; }

    private boolean register() {
        try {
            watcher = java.nio.file.FileSystems.getDefault().newWatchService();
            Set<Path> dirs = new LinkedHashSet<>();
            paths.forEach(path -> dirs.add(Files.isDirectory(path) ? path : path.getParent()));
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

    private void watch() {
        while (watcher != null) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (var event : key.pollEvents()) {
                Path dir = (Path) key.watchable();
                Path target = dir.resolve(String.valueOf(event.context()));
                if (paths.stream().anyMatch(path -> target.startsWith(path) || target.equals(path))) {
                    changed.accept(target);
                }
            }
            key.reset();
        }
    }

    @Override
    public void close() {
        if (watcher != null) {
            try {
                WatchService current = watcher;
                watcher = null;
                current.close();
            } catch (IOException e) {
                error = "persistência: " + e.getMessage();
            }
        }
    }
}
