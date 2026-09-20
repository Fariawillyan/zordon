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
package zordon.core.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O modo e o dispositivo escolhidos, em {@code ~/.zordon/state/voice.json}
 * (SPEC-006 §9). Nada de áudio, transcrição ou nível.
 */
public final class VoiceStore {

    /** @param deviceId {@code null} usa o padrão do Windows. */
    public record Saved(VoiceMode mode, String deviceId) {

        public Saved {
            Objects.requireNonNull(mode, "mode");
            if (mode == VoiceMode.OPEN) {
                throw new IllegalArgumentException("open não é persistido");
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(VoiceStore.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final Path file;

    public VoiceStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Na dúvida, o microfone fica desligado: arquivo ausente ou ilegível dá {@code off}. */
    public Saved load() {
        if (!Files.exists(file)) {
            return new Saved(VoiceMode.OFF, null);
        }
        try {
            Map<?, ?> saved = json.readValue(file.toFile(), Map.class);
            VoiceMode mode = VoiceMode.parse(saved.get("mode"));
            Object device = saved.get("deviceId");
            return new Saved(mode == VoiceMode.OPEN ? VoiceMode.OFF : mode, device instanceof String id ? id : null);
        } catch (IOException | RuntimeException e) {
            log.warn("{} ilegível; voz fica desligada até a próxima escolha: {}", file, e.getMessage());
            return new Saved(VoiceMode.OFF, null);
        }
    }

    public void save(Saved saved) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("mode", saved.mode().wire());
        content.put("deviceId", saved.deviceId());
        try {
            Files.createDirectories(file.getParent());
            Path next = file.resolveSibling(file.getFileName() + ".next");
            Files.writeString(next, json.writeValueAsString(content));
            restrict(next);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("não foi possível gravar {}: {}", file, e.getMessage());
        }
    }

    private static void restrict(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Sistema de arquivos sem POSIX (testes no Windows); o núcleo roda no WSL.
        }
    }
}
