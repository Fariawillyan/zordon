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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
 */
@Spec("SPEC-016")
public final class ProcessRunner {

    public static final int MAX_OUTPUT = 64 * 1024;
    private static final List<String> KEPT_ENV = List.of("PATH", "HOME", "LANG", "LC_ALL", "USER", "TERM");

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {}

    /** Um processo longo (servidor MCP, SPEC-020): as pontas de entrada e saída e o encerramento. */
    public static final class Live implements AutoCloseable {
        private final Process process;

        private Live(Process process) {
            this.process = process;
        }

        public java.io.OutputStream stdin() {
            return process.getOutputStream();
        }

        public InputStream stdout() {
            return process.getInputStream();
        }

        public boolean alive() {
            return process.isAlive();
        }

        /** Fecha a entrada, espera 5 s e só então encerra à força (MCP §2, desligamento). */
        @Override
        public void close() {
            try {
                process.getOutputStream().close();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } catch (IOException e) {
                process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /** Inicia um processo longo, pelo mesmo caminho validado; o {@code stderr} é descartado em fluxo. */
    public Live start(Gatekeeper.Permit.Granted permit, Path cwd) throws IOException {
        List<String> command = permit.action().command();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("a autorização não é de um comando");
        }
        String program = switch (validator.validate(command)) {
            case CommandValidator.Validation.Denied denied -> throw new SecurityException(denied.reason());
            case CommandValidator.Validation.Accepted accepted -> accepted.program();
        };
        ProcessBuilder builder = new ProcessBuilder(concat(program, command.subList(1, command.size())));
        builder.directory(cwd.toFile());
        builder.environment().keySet().removeIf(key -> !KEPT_ENV.contains(key));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return new Live(builder.start());
    }

    private final CommandValidator validator;

    public ProcessRunner(CommandValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Result run(Gatekeeper.Permit.Granted permit, Path cwd, Duration timeout)
            throws IOException, InterruptedException {
        return run(permit, cwd, timeout, null);
    }

    /** @param stdin texto para a entrada padrão do filho; {@code null} fecha a entrada vazia */
    public Result run(Gatekeeper.Permit.Granted permit, Path cwd, Duration timeout, String stdin)
            throws IOException, InterruptedException {
        List<String> command = permit.action().command();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("a autorização não é de um comando");
        }
        // A validação é refeita aqui: o programa que roda é o do catálogo, nunca o texto recebido.
        String program = switch (validator.validate(command)) {
            case CommandValidator.Validation.Denied denied -> throw new SecurityException(denied.reason());
            case CommandValidator.Validation.Accepted accepted -> accepted.program();
        };
        ProcessBuilder builder = new ProcessBuilder(concat(program, command.subList(1, command.size())));
        builder.directory(cwd.toFile());
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(key -> !KEPT_ENV.contains(key));
        Process process = builder.start();
        if (stdin == null) {
            process.getOutputStream().close();
        } else {
            Thread.ofVirtual().start(() -> {
                try (var in = process.getOutputStream()) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    // O filho saiu antes de ler tudo: o código de saída conta o resto.
                }
            });
        }
        Capture out = new Capture(process.getInputStream());
        Capture err = new Capture(process.getErrorStream());
        Thread readOut = Thread.ofVirtual().start(out);
        Thread readErr = Thread.ofVirtual().start(err);
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        readOut.join(2_000);
        readErr.join(2_000);
        return new Result(finished ? process.exitValue() : -1, out.text(), err.text(), !finished,
                out.truncated || err.truncated);
    }

    private static List<String> concat(String program, List<String> args) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(args.size() + 1);
        out.add(program);
        out.addAll(args);
        return out;
    }

    /** Lê tudo, guarda só os primeiros 64 KB: um filho verborrágico não enche a memória. */
    private static final class Capture implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        volatile boolean truncated;

        Capture(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (in) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    int room = MAX_OUTPUT - kept.size();
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
    }
}
