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
import java.nio.file.Path;
import java.time.Duration;
import zordon.api.trace.Spec;

/**
 * O único lugar do Zordon que inicia processo (ADR-0007, SPEC-016 CA-2).
 *
 * <ul>
 *   <li>Só roda o comando exato de uma {@link Gatekeeper.Permit.Granted}.
 *   <li>Sem shell: lista de argumentos, o programa resolvido pelo catálogo.
 *   <li>Ambiente mínimo: nenhum segredo do núcleo chega ao filho.
 *   <li>Saída limitada e prazo; estourou, o filho é encerrado.
 * </ul>
 *
 * <p>Validar e montar o comando fica em {@link ProcessCommand}; esperar e
 * capturar, em {@link ProcessExecution}.
 */
@Spec("SPEC-016")
public final class ProcessRunner {

    public static final int MAX_OUTPUT = 64 * 1024;

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {}

    private final ProcessCommand command;

    public ProcessRunner(CommandValidator validator) {
        this.command = new ProcessCommand(validator);
    }

    /** Inicia um processo longo, pelo mesmo caminho validado; o {@code stderr} é descartado em fluxo. */
    public LiveProcess start(Gatekeeper.Permit.Granted permit, Path cwd) throws IOException {
        return command.startLive(permit, cwd);
    }

    public Result run(Gatekeeper.Permit.Granted permit, Path cwd, Duration timeout)
            throws IOException, InterruptedException {
        return run(permit, cwd, timeout, null);
    }

    /** @param stdin texto para a entrada padrão do filho; {@code null} fecha a entrada vazia */
    public Result run(Gatekeeper.Permit.Granted permit, Path cwd, Duration timeout, String stdin)
            throws IOException, InterruptedException {
        return ProcessExecution.run(command.builder(permit, cwd), timeout, stdin);
    }

}
