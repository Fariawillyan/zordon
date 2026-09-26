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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Monta o processo de uma autorização: a validação é refeita aqui, e o programa
 * que roda é o do catálogo, nunca o texto recebido. Sem shell, ambiente mínimo.
 */
final class ProcessCommand {

    private static final List<String> KEPT_ENV = List.of("PATH", "HOME", "LANG", "LC_ALL", "USER", "TERM");

    private final CommandValidator validator;

    ProcessCommand(CommandValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /** Um processo longo: o {@code stderr} é descartado em fluxo, para o filho nunca travar escrevendo nele. */
    LiveProcess startLive(Gatekeeper.Permit.Granted permit, Path cwd) throws IOException {
        return ProcessLive.start(builder(permit, cwd));
    }

    ProcessBuilder builder(Gatekeeper.Permit.Granted permit, Path cwd) {
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
        return builder;
    }

    private static List<String> concat(String program, List<String> args) {
        List<String> out = new ArrayList<>(args.size() + 1);
        out.add(program);
        out.addAll(args);
        return out;
    }
}
