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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serialização do manifesto, isolada do ciclo de vida do cofre. */
final class VaultManifest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private VaultManifest() {}

    static void write(Path item, Map<String, Object> manifest) throws IOException {
        Path part = item.resolve("manifest.json.part");
        JSON.writeValue(part.toFile(), manifest);
        Files.move(part, item.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path item) throws IOException {
        return JSON.readValue(item.resolve("manifest.json").toFile(), LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    static QuarantineVault.Item describe(Map<String, Object> manifest) {
        List<Map<String, Object>> entries = (List<Map<String, Object>>) manifest.getOrDefault("entries", List.of());
        long bytes = entries.stream().mapToLong(entry -> ((Number) entry.getOrDefault("bytes", 0)).longValue()).sum();
        List<String> notes = entries.stream()
                .filter(entry -> String.valueOf(entry.get("state")).contains("-"))
                .map(entry -> entry.get("original") + ": " + entry.get("state")).toList();
        return new QuarantineVault.Item(String.valueOf(manifest.get("vaultId")), String.valueOf(manifest.get("ts")),
                String.valueOf(manifest.get("reason")), String.valueOf(manifest.get("root")), entries.size(), bytes,
                Boolean.TRUE.equals(manifest.get("restored")), notes);
    }
}
