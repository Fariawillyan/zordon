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

import java.io.IOException;
import java.time.Duration;

/** Roda um comando autorizado com prazo e saída limitada; estourou o prazo, o filho é encerrado. */
final class ProcessExecution {

    private ProcessExecution() {}

    /** @param builder já validado e montado por {@link ProcessCommand} */
    static ProcessRunner.Result run(ProcessBuilder builder, Duration timeout, String stdin)
            throws IOException, InterruptedException {
        Process process = builder.start();
        if (stdin == null) {
            process.getOutputStream().close();
        } else {
            Thread.ofVirtual().start(() -> ProcessCapture.write(process, stdin));
        }
        ProcessCapture out = ProcessCapture.start(process.getInputStream());
        ProcessCapture err = ProcessCapture.start(process.getErrorStream());
        boolean finished = process.waitFor(timeout);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(Duration.ofSeconds(5));
        }
        out.await(Duration.ofSeconds(2));
        err.await(Duration.ofSeconds(2));
        return new ProcessRunner.Result(finished ? process.exitValue() : -1, out.text(), err.text(), !finished,
                out.truncated() || err.truncated());
    }
}
