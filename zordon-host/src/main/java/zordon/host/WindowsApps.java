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
package zordon.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.zwp.CoreConnection;

/**
 * O catálogo de aplicativos do Windows e a abertura de um item dele
 * (SPEC-016 CA-3). O catálogo são os atalhos do Menu Iniciar; o núcleo só pode
 * pedir um id que este catálogo deu. Abrir é pelo {@code java.awt.Desktop}:
 * sem {@code ProcessBuilder} e sem código nativo no host.
 */
@Spec("SPEC-016")
final class WindowsApps {

    /** Abre um atalho. Em produção, {@code java.awt.Desktop}; nos testes, um falso. */
    @FunctionalInterface
    interface Opener {
        void open(Path shortcut) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(WindowsApps.class);
    private static final int MAX_DEPTH = 4;

    private final List<Path> roots;
    private final Opener opener;
    private final Map<String, Path> byId = new ConcurrentHashMap<>();

    WindowsApps(List<Path> roots, Opener opener) {
        this.roots = List.copyOf(roots);
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** Os Menus Iniciar do usuário e de todos os usuários. */
    static WindowsApps system() {
        List<Path> roots = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String programData = System.getenv("ProgramData");
        if (appData != null) {
            roots.add(Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs"));
        }
        if (programData != null) {
            roots.add(Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs"));
        }
        return new WindowsApps(roots, shortcut -> {
            if (!java.awt.Desktop.isDesktopSupported()) {
                throw new IOException("este Windows não permite abrir atalhos por aqui");
            }
            java.awt.Desktop.getDesktop().open(shortcut.toFile());
        });
    }

    void registerOn(CoreConnection connection) {
        connection.handle("windows.apps", params -> Map.of("apps", list()))
                .handle("windows.openApp", this::open);
    }

    /** Atalhos {@code .lnk}, sem desinstaladores, sem repetição de nome. */
    List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        byId.clear();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root, MAX_DEPTH)) {
                files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lnk"))
                        .sorted()
                        .forEach(path -> {
                            String file = path.getFileName().toString();
                            String name = file.substring(0, file.length() - 4).strip();
                            String lower = name.toLowerCase(Locale.ROOT);
                            if (lower.contains("uninstall") || lower.contains("desinstal")) {
                                return;
                            }
                            String id = id(path);
                            byId.put(id, path);
                            byName.putIfAbsent(lower, Map.of("id", id, "name", name));
                        });
            } catch (IOException e) {
                log.warn("Menu Iniciar ilegível em {}: {}", root, e.getMessage());
            }
        }
        log.info("catálogo de aplicativos: {} atalhos", byName.size());
        return List.copyOf(byName.values());
    }

    private Map<String, Object> open(Map<String, Object> params) {
        Object id = params.get("id");
        Path shortcut = id == null ? null : byId.get(String.valueOf(id));
        if (shortcut == null) {
            // Só abre o que este catálogo listou: o núcleo não manda caminho (SPEC-016 §3).
            return Map.of("opened", false, "reason", "aplicativo fora do catálogo");
        }
        try {
            opener.open(shortcut);
            log.info("aberto: {}", shortcut.getFileName());
            return Map.of("opened", true, "name", shortcut.getFileName().toString().replaceFirst("(?i)\\.lnk$", ""));
        } catch (IOException | RuntimeException e) {
            log.warn("não abriu {}: {}", shortcut.getFileName(), e.getMessage());
            return Map.of("opened", false, "reason", String.valueOf(e.getMessage()));
        }
    }

    private static String id(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(path.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
