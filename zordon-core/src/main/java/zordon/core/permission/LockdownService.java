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
package zordon.core.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O kill switch (docs/security/model.md §8, SPEC-015 CA-6): "Pausar Zordon" põe
 * tudo acima de GREEN em negado, e o estado sobrevive ao reinício. Sair exige a
 * tela: quem chama {@link #resume} garante que o pedido veio de um desktop.
 */
@Spec("SPEC-015")
public final class LockdownService {

    private static final Logger log = LoggerFactory.getLogger(LockdownService.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final Path file;
    private final Clock clock;
    /** Publica {@code LOCKDOWN_ENTERED}/{@code LOCKDOWN_EXITED}: (entrou?, payload). */
    private final BiConsumer<Boolean, Map<String, Object>> events;
    private Map<String, Object> state;

    public LockdownService(Path file, Clock clock, BiConsumer<Boolean, Map<String, Object>> events) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.state = load(file);
        if (active()) {
            log.warn("Zordon começa em lockdown (só leitura), desde {}: {}", state.get("since"), state.get("reason"));
        }
    }

    public synchronized boolean active() {
        return Boolean.TRUE.equals(state.get("active"));
    }

    public synchronized Map<String, Object> status() {
        return Map.copyOf(state);
    }

    public synchronized Map<String, Object> enter(String reason, String trigger) {
        if (!active()) {
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("active", true);
            next.put("since", clock.instant().toString());
            next.put("reason", reason == null || reason.isBlank() ? "pausado pelo usuário" : reason);
            next.put("trigger", trigger);
            save(next);
            log.warn("lockdown: {} ({})", next.get("reason"), trigger);
            events.accept(true, Map.of("reason", next.get("reason"), "trigger", trigger, "auto", false));
        }
        return status();
    }

    /** Só pela tela: o chamador confere que a sessão é de um desktop. */
    public synchronized Map<String, Object> resume() {
        if (active()) {
            save(Map.of("active", false, "resumedAt", clock.instant().toString()));
            log.warn("lockdown encerrado pelo usuário");
            events.accept(false, Map.of("by", "user"));
        }
        return status();
    }

    private void save(Map<String, Object> next) {
        try {
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(part, json.writeValueAsString(next));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            state = Map.copyOf(next);
        } catch (IOException e) {
            // Sem gravar, o estado em memória ainda protege; o próximo reinício é que não saberia.
            state = Map.copyOf(next);
            log.error("estado do lockdown não gravado em {}: {}", file, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path file) {
        if (!Files.exists(file)) {
            return Map.of("active", false);
        }
        try {
            return Map.copyOf(json.readValue(file.toFile(), Map.class));
        } catch (IOException e) {
            // Arquivo ilegível: na dúvida, só leitura. Quem o corrompeu não ganha a saída do lockdown.
            log.error("estado do lockdown ilegível em {}: {}; começando em lockdown", file, e.getMessage());
            return Map.of("active", true, "since", "desconhecido", "reason", "estado do lockdown ilegível",
                    "trigger", "startup");
        }
    }
}
