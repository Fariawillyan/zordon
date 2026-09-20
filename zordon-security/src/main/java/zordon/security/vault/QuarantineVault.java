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
package zordon.security.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O cofre de quarentena (SPEC-017, ADR-0015): o lugar para onde vai o que o
 * usuário queria "apagar". Só move, com hash e origem, e devolve quando pedido.
 * Não existe operação de exclusão aqui nem em lugar nenhum.
 */
@Spec("SPEC-017")
public final class QuarantineVault {

    /** Um item do cofre, como a tela e o manifesto o descrevem. */
    public record Item(String vaultId, String ts, String reason, String root, int files, long bytes,
            boolean restored, List<String> notes) {}

    public static final int MAX_FILES = 10_000;

    private static final Logger log = LoggerFactory.getLogger(QuarantineVault.class);
    private static final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;
    private final Clock clock;

    public QuarantineVault(Path root, Clock clock) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path root() {
        return root;
    }

    /** Os arquivos sob {@code target} (ele mesmo, se for arquivo), em ordem. */
    public static List<Path> filesUnder(Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            return List.of(target);
        }
        try (Stream<Path> walk = Files.walk(target)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().limit(MAX_FILES + 1L).toList();
            if (files.size() > MAX_FILES) {
                throw new IOException("mais de " + MAX_FILES + " arquivos: grande demais para uma confirmação legível");
            }
            return files;
        }
    }

    /**
     * Move {@code files} (todos sob {@code base}) para um item novo do cofre. O
     * manifesto é gravado antes do primeiro arquivo sair e depois de cada um.
     */
    public synchronized Item store(List<Path> files, Path base, String reason) throws IOException {
        String vaultId = "q-" + clock.instant().toString().replace(":", "").replace(".", "-") + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path item = root.resolve(vaultId);
        Path payload = Files.createDirectories(item.resolve("files"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("vaultId", vaultId);
        manifest.put("ts", clock.instant().toString());
        manifest.put("reason", reason);
        manifest.put("root", base.toString());
        manifest.put("restored", false);
        List<Map<String, Object>> entries = new ArrayList<>();
        manifest.put("entries", entries);
        write(item, manifest);
        int index = 0;
        for (Path file : files) {
            String sha = sha256(file);
            long size = Files.size(file);
            Path stored = payload.resolve(String.format("%05d-%s", index++, file.getFileName()));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("original", file.toAbsolutePath().toString());
            entry.put("stored", item.relativize(stored).toString());
            entry.put("sha256", sha);
            entry.put("bytes", size);
            entry.put("state", "moving");
            entries.add(entry);
            write(item, manifest);
            Files.move(file, stored);
            boolean intact = sha.equals(sha256(stored));
            entry.put("state", intact ? "stored" : "stored-hash-mismatch");
            write(item, manifest);
        }
        log.info("quarentena {}: {} arquivos de {}", vaultId, files.size(), base);
        return describe(manifest);
    }

    /** Devolve cada arquivo ao lugar de origem, sem nunca sobrescrever o que estiver lá. */
    public synchronized Item restore(String vaultId) throws IOException {
        Path item = locate(vaultId);
        Map<String, Object> manifest = read(item);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) manifest.get("entries");
        for (Map<String, Object> entry : entries) {
            if (!String.valueOf(entry.get("state")).startsWith("stored")) {
                continue;
            }
            Path original = Path.of(String.valueOf(entry.get("original")));
            Path stored = item.resolve(String.valueOf(entry.get("stored")));
            try {
                Files.createDirectories(original.getParent());
                Files.move(stored, original);   // sem REPLACE_EXISTING: nunca sobrescreve
                entry.put("state", "restored");
            } catch (FileAlreadyExistsException e) {
                entry.put("state", "stored-original-occupied");
            }
            write(item, manifest);
        }
        boolean all = entries.stream().allMatch(entry -> "restored".equals(entry.get("state")));
        manifest.put("restored", all);
        manifest.put("restoredAt", clock.instant().toString());
        write(item, manifest);
        log.info("quarentena {} restaurada{}", vaultId, all ? "" : " em parte");
        return describe(manifest);
    }

    public synchronized List<Item> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> items = Files.list(root)) {
            List<Item> out = new ArrayList<>();
            for (Path item : items.filter(path -> Files.isRegularFile(path.resolve("manifest.json"))).sorted().toList()) {
                out.add(describe(read(item)));
            }
            return out.reversed();
        } catch (IOException e) {
            log.warn("cofre ilegível em {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    public synchronized Item get(String vaultId) throws IOException {
        return describe(read(locate(vaultId)));
    }

    private Path locate(String vaultId) throws IOException {
        if (vaultId == null || !vaultId.matches("q-[0-9A-Za-z-]+")) {
            throw new IOException("id de quarentena inválido: " + vaultId);
        }
        Path item = root.resolve(vaultId);
        if (!Files.isRegularFile(item.resolve("manifest.json"))) {
            throw new IOException("não existe o item de quarentena " + vaultId);
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private static Item describe(Map<String, Object> manifest) {
        List<Map<String, Object>> entries = (List<Map<String, Object>>) manifest.getOrDefault("entries", List.of());
        long bytes = entries.stream().mapToLong(entry -> ((Number) entry.getOrDefault("bytes", 0)).longValue()).sum();
        List<String> notes = entries.stream()
                .filter(entry -> String.valueOf(entry.get("state")).contains("-"))
                .map(entry -> entry.get("original") + ": " + entry.get("state")).toList();
        return new Item(String.valueOf(manifest.get("vaultId")), String.valueOf(manifest.get("ts")),
                String.valueOf(manifest.get("reason")), String.valueOf(manifest.get("root")), entries.size(), bytes,
                Boolean.TRUE.equals(manifest.get("restored")), notes);
    }

    private static void write(Path item, Map<String, Object> manifest) throws IOException {
        Path part = item.resolve("manifest.json.part");
        json.writeValue(part.toFile(), manifest);
        Files.move(part, item.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path item) throws IOException {
        return json.readValue(item.resolve("manifest.json").toFile(), LinkedHashMap.class);
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
