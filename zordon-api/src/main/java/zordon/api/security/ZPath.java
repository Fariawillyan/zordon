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
package zordon.api.security;

import java.util.Locale;
import java.util.Objects;

/**
 * Caminho com o mundo de origem explícito (Windows ↔ WSL, R8). Nenhuma API de
 * ação aceita caminho como {@code String} solta.
 *
 * @param canonical absoluto, com separador {@code /}; no Windows, {@code C:/Users/...}
 */
public record ZPath(Origin origin, String canonical) {

    public enum Origin { WINDOWS, WSL }

    public ZPath {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(canonical, "canonical");
    }

    public static ZPath ofWsl(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("caminho WSL precisa ser absoluto: " + path);
        }
        return new ZPath(Origin.WSL, path);
    }

    public static ZPath ofWindows(String path) {
        String slashed = path.replace('\\', '/');
        if (slashed.length() < 3 || slashed.charAt(1) != ':' || slashed.charAt(2) != '/') {
            throw new IllegalArgumentException("caminho Windows precisa ser absoluto (C:\\...): " + path);
        }
        return new ZPath(Origin.WINDOWS, Character.toUpperCase(slashed.charAt(0)) + slashed.substring(1));
    }

    /** O mesmo caminho visto do WSL: {@code C:/x} vira {@code /mnt/c/x}. */
    public String toWsl() {
        if (origin == Origin.WSL) {
            return canonical;
        }
        return "/mnt/" + Character.toLowerCase(canonical.charAt(0)) + canonical.substring(2);
    }

    /** O mesmo caminho visto do Windows; só existe para caminhos sob {@code /mnt/<letra>}. */
    public String toWindows() {
        if (origin == Origin.WINDOWS) {
            return canonical.replace('/', '\\');
        }
        if (canonical.matches("/mnt/[a-z](/.*)?")) {
            String rest = canonical.length() > 6 ? canonical.substring(6) : "/";
            return (Character.toUpperCase(canonical.charAt(5)) + ":" + rest).replace('/', '\\');
        }
        throw new IllegalStateException("caminho do WSL sem equivalente no Windows: " + canonical);
    }

    @Override
    public String toString() {
        return origin == Origin.WINDOWS ? toWindows() : canonical;
    }

    /** Para comparação: o Windows não diferencia maiúsculas. */
    public String comparable() {
        String wsl = toWsl();
        return origin == Origin.WINDOWS || wsl.startsWith("/mnt/") ? wsl.toLowerCase(Locale.ROOT) : wsl;
    }
}
