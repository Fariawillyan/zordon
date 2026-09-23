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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;

/**
 * As áreas de caminho do {@code config.toml} (docs/security/model.md §4):
 * {@code forbidden} vence tudo; {@code workspaces} é onde se escreve;
 * {@code readable} é onde se lê. Imutável: lida na inicialização.
 *
 * <p>Padrões aceitam prefixo absoluto ({@code D:/projetos}, {@code ~/dev}) ou
 * glob ({@code **}{@code /.git/**}). Tudo é comparado na forma do WSL, e
 * caminhos do Windows sem diferença de maiúsculas.
 */
@Spec("SPEC-014")
public final class PathPolicy {

    /** Como um caminho canônico cai nas áreas. */
    public record Verdict(boolean forbidden, boolean install, boolean critical, boolean workspace, boolean readable) {}

    private final List<Rule> workspaces;
    private final List<Rule> readable;
    private final List<Rule> forbidden;
    private final List<Rule> critical;
    private final List<Rule> install;
    private final String home;
    private final List<List<String>> raw;

    /**
     * @param home o {@code $HOME} do WSL, para expandir {@code ~}
     * @param critical fora de {@code forbidden}, mas sempre RED ({@code %APPDATA%}, {@code /etc})
     * @param install onde o Zordon está instalado: escrita sempre negada
     */
    public PathPolicy(String home, List<String> workspaces, List<String> readable, List<String> forbidden,
            List<String> critical, List<String> install) {
        this.home = Objects.requireNonNull(home, "home");
        this.raw = List.of(List.copyOf(workspaces), List.copyOf(readable), List.copyOf(forbidden),
                List.copyOf(critical), List.copyOf(install));
        this.workspaces = rules(workspaces);
        this.readable = rules(readable);
        this.forbidden = rules(forbidden);
        this.critical = rules(critical);
        this.install = rules(install);
    }

    /** As raízes dos workspaces, expandidas e sem curinga: o que é projeto do usuário (SPEC-021 CA-7). */
    public List<String> workspaceRoots() {
        return workspaces.stream().map(Rule::prefix).filter(Objects::nonNull).toList();
    }

    /** Os padrões da documentação, para quando o {@code config.toml} não diz nada. */
    public static PathPolicy defaults(String home, List<String> workspaces, List<String> readable) {
        return new PathPolicy(home, workspaces, readable,
                List.of("C:/Windows", "C:/Program Files", "C:/Program Files (x86)", "**/.git/**", "**/.ssh/**",
                        "**/node_modules/**", "**/.env", "**/*.env", "**/*.pem", "**/*.key",
                        "~/.zordon/secrets.env", "~/.zordon/secrets.age", "~/.zordon/key", "~/.gnupg/**",
                        "~/.zordon/quarantine/**", "~/.zordon/state/**"),
                List.of("C:/Users/*/AppData/**", "C:/ProgramData/**", "/etc/**", "/usr/**", "/boot/**",
                        "/var/lib/**"),
                List.of("~/.local/share/zordon/**", "C:/Users/*/AppData/Local/Programs/Zordon/**",
                        "/etc/systemd/system/zordon*"));
    }

    /** A mesma política com mais padrões proibidos: o usuário só pode apertar, nunca afrouxar. */
    public PathPolicy withForbidden(List<String> extra) {
        List<String> forbiddenAll = new ArrayList<>(raw.get(2));
        forbiddenAll.addAll(extra);
        return new PathPolicy(home, raw.get(0), raw.get(1), forbiddenAll, raw.get(3), raw.get(4));
    }

    public Verdict classify(ZPath path) {
        String subject = path.comparable();
        return new Verdict(matches(forbidden, subject), matches(install, subject), matches(critical, subject),
                matches(workspaces, subject), matches(readable, subject) || matches(workspaces, subject));
    }

    /**
     * Absoluto e canônico, com symlinks resolvidos (SPEC-014 CA-4): sem isso,
     * {@code ../../Windows} ou um link para {@code ~/.ssh} passariam por uma
     * comparação de prefixo. A parte que ainda não existe é anexada ao ancestral
     * real mais próximo.
     *
     * @param base de onde um caminho relativo parte
     */
    public ZPath canonical(String raw, ZPath base) throws IOException {
        ZPath absolute = absolute(raw.strip(), base, raw);
        Path resolved = realPath(Path.of(absolute.toWsl()).normalize());
        String out = resolved.toString();
        if (absolute.origin() == ZPath.Origin.WINDOWS && out.matches("/mnt/[a-z](/.*)?")) {
            String rest = out.length() > 6 ? out.substring(6) : "/";
            return ZPath.ofWindows(Character.toUpperCase(out.charAt(5)) + ":" + rest);
        }
        return ZPath.ofWsl(out);
    }

    /** De onde o texto parte: unidade do Windows, {@code ~}, raiz do WSL, ou relativo à base. */
    private ZPath absolute(String text, ZPath base, String raw) {
        if (text.matches("^[A-Za-z]:[\\\\/].*")) {
            return ZPath.ofWindows(text);
        }
        if (text.equals("~") || text.startsWith("~/")) {
            return ZPath.ofWsl(home + text.substring(1));
        }
        if (text.startsWith("/")) {
            return ZPath.ofWsl(text);
        }
        Objects.requireNonNull(base, "caminho relativo sem diretório de partida: " + raw);
        return base.origin() == ZPath.Origin.WINDOWS
                ? ZPath.ofWindows(base.canonical() + "/" + text)
                : ZPath.ofWsl(base.canonical() + "/" + text);
    }

    /**
     * Resolve links no trecho que já existe e recoloca o resto por cima.
     *
     * <p>Um caminho que ainda não existe não tem {@code toRealPath}; resolver só o
     * que existe é o que impede um link simbólico de escapar da política.
     */
    private static Path realPath(Path wsl) throws IOException {
        Path existing = wsl;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing == null ? wsl : existing.toRealPath().resolve(existing.relativize(wsl)).normalize();
    }

    private record Rule(String prefix, PathMatcher glob) {
        boolean matches(String subject) {
            if (glob != null) {
                return glob.matches(Path.of(subject));
            }
            return subject.equals(prefix) || subject.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
        }
    }

    private List<Rule> rules(List<String> patterns) {
        List<Rule> out = new ArrayList<>();
        for (String pattern : patterns) {
            String normal = normalize(pattern);
            if (normal.contains("*") || normal.contains("?")) {
                out.add(new Rule(null, FileSystems.getDefault().getPathMatcher("glob:" + normal)));
                if (normal.endsWith("/**")) {
                    // "**/.git/**" também protege o próprio ".git".
                    out.add(new Rule(null, FileSystems.getDefault()
                            .getPathMatcher("glob:" + normal.substring(0, normal.length() - 3))));
                }
            } else {
                out.add(new Rule(normal, null));
            }
        }
        return List.copyOf(out);
    }

    /** Padrão na forma de comparação: {@code ~} expandido, Windows em {@code /mnt/<letra>} e minúsculo. */
    private String normalize(String pattern) {
        String p = pattern.replace('\\', '/');
        if (p.equals("~") || p.startsWith("~/")) {
            p = home + p.substring(1);
        }
        if (p.matches("^[A-Za-z]:/.*") || p.matches("^[A-Za-z]:$")) {
            p = "/mnt/" + Character.toLowerCase(p.charAt(0)) + p.substring(2);
        }
        if (p.startsWith("/mnt/") || !p.startsWith("/")) {
            p = p.toLowerCase(Locale.ROOT);
        }
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private static boolean matches(List<Rule> rules, String subject) {
        for (Rule rule : rules) {
            if (rule.matches(subject)) {
                return true;
            }
        }
        return false;
    }
}
