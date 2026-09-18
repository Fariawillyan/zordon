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
package zordon.desktop;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Onde o cliente procura o {@code endpoint.json}.
 *
 * <p>No Windows é o perfil do usuário; em desenvolvimento dentro do WSL é o
 * {@code ZORDON_HOME}. Nunca se deriva um do outro: o usuário do Windows e o do WSL
 * podem ter nomes diferentes — nesta máquina, têm
 * (docs/architecture/windows-wsl.md, R21).
 */
public final class DesktopPaths {

    public static Path endpointFile() {
        return endpointFile(System.getenv(), System.getProperty("user.home"));
    }

    static Path endpointFile(java.util.Map<String, String> environment, String userHome) {
        return Optional.ofNullable(environment.get("ZORDON_HOME"))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .orElseGet(() -> Path.of(userHome, ".zordon"))
                .resolve("endpoint.json");
    }

    private DesktopPaths() {}
}
