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
package zordon.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Lê tudo, guarda só os primeiros 64 KB: um filho verborrágico não enche a memória. */
final class ProcessCapture implements Runnable {

    private final InputStream in;
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private volatile boolean truncated;
    private Thread reader;

    private ProcessCapture(InputStream in) {
        this.in = in;
    }

    /** Começa a ler numa thread virtual; {@link #await} espera o fim. */
    static ProcessCapture start(InputStream in) {
        ProcessCapture capture = new ProcessCapture(in);
        capture.reader = Thread.ofVirtual().start(capture);
        return capture;
    }

    void await(Duration limit) throws InterruptedException {
        reader.join(limit);
    }

    static void write(Process process, String text) {
        try (var output = process.getOutputStream()) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // O filho saiu antes de ler tudo: o código de saída conta o resto.
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[8192];
        try (in) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                int room = ProcessRunner.MAX_OUTPUT - kept.size();
                if (room > 0) {
                    kept.write(buffer, 0, Math.min(room, read));
                }
                if (read > room) {
                    truncated = true;
                }
            }
        } catch (IOException e) {
            truncated = true;
        }
    }

    String text() {
        return kept.toString(StandardCharsets.UTF_8);
    }

    boolean truncated() {
        return truncated;
    }
}
