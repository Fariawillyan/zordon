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
package zordon.zwp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import zordon.api.zwp.EndpointFile;

/** O {@code endpoint.json}: onde o núcleo está, e quanto esperar antes de procurar de novo. */
final class EndpointWatch {

    private static final Duration POLL = Duration.ofMillis(250);

    private final Path file;
    private final EndpointFileStore store = new EndpointFileStore();
    private final ReconnectBackoff backoff = new ReconnectBackoff();

    EndpointWatch(Path file) {
        this.file = file;
    }

    Optional<EndpointFile> read() {
        return store.read(file);
    }

    void connected() {
        backoff.reset();
    }

    /**
     * Espera o backoff, mas acorda assim que o {@code endpoint.json} mudar: um
     * núcleo que acabou de reiniciar não deve esperar dez segundos para ser achado.
     */
    void waitBeforeRetry(BooleanSupplier running) {
        long deadline = System.nanoTime() + backoff.nextDelay().toNanos();
        long seen = lastModified();
        while (running.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (lastModified() != seen) {
                backoff.reset();
                return;
            }
        }
    }

    private long lastModified() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }
}
