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
package zordon.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import zordon.ai.cli.CliRunner;

/**
 * O provider por assinatura roda pelo caminho mediado (SPEC-018), e esse caminho
 * só existe depois da segurança montada. Até lá, o pedido é recusado com motivo.
 */
final class DeferredCliRunner implements CliRunner {

    private final AtomicReference<CliRunner> current = new AtomicReference<>();
    private final SecuritySettingsLoader settings;

    DeferredCliRunner(SecuritySettingsLoader settings) {
        this.settings = settings;
    }

    void set(CliRunner runner) {
        current.set(runner);
    }

    @Override
    public Result run(List<String> argv, String stdin, Duration timeout) throws Exception {
        CliRunner runner = current.get();
        if (runner == null) {
            throw new IllegalStateException("o núcleo ainda está iniciando");
        }
        return runner.run(argv, stdin, timeout);
    }

    @Override
    public boolean available(String program) {
        return settings.load().catalog().containsKey(program);
    }
}
