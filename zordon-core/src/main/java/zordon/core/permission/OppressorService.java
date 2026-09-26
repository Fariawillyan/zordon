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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.security.MasterPassword;

/**
 * OPPRESSOR MODE: enquanto ativo, o {@code Gatekeeper} libera toda ação sem
 * consultar o motor de permissão (SPEC-036, ADR-0041).
 *
 * <p>A senha mestre é a autorização. Não há segunda pergunta depois dela: nem
 * confirmação por ação, nem teto por origem, nem lockdown. A auditoria segue
 * gravando tudo, e gravar não é aprovar — nada espera pelo registro.
 *
 * <p>Ao contrário do {@link LockdownService}, este estado <b>não sobrevive ao
 * reinício</b>. O seguro do lockdown é continuar fechado, então ele persiste; o
 * seguro daqui é acordar fechado. Um núcleo que reiniciou sozinho — por queda,
 * por atualização, por supervisor — não deve voltar com o motor de permissão
 * desligado sem que ninguém tenha digitado nada.
 */
@Spec("SPEC-036")
public final class OppressorService {

    private static final Logger log = LoggerFactory.getLogger(OppressorService.class);
    private static final Set<PosixFilePermission> ONLY_OWNER =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path file;
    private final Clock clock;
    /** Publica {@code OPPRESSOR_ENTERED}/{@code OPPRESSOR_EXITED}: (entrou?, payload). */
    private final BiConsumer<Boolean, Map<String, Object>> events;

    private volatile boolean active;
    private Instant since;
    private String trigger;

    public OppressorService(Path file, Clock clock, BiConsumer<Boolean, Map<String, Object>> events) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Sem volátil intermediário: é lido a cada ação, do caminho quente do Gatekeeper. */
    public boolean active() {
        return active;
    }

    /** Se existe senha cadastrada. Sem ela não há como entrar. */
    public boolean configured() {
        return Files.exists(file);
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", active);
        out.put("configured", configured());
        if (active) {
            out.put("since", since.toString());
            out.put("trigger", trigger);
        }
        return Map.copyOf(out);
    }

    /**
     * Entra no modo se a senha conferir.
     *
     * <p>Zera o vetor da senha antes de sair, inclusive quando ela está errada:
     * o chamador entrega a senha e perde a posse dela aqui.
     *
     * @return {@code true} se entrou; {@code false} se a senha não confere ou
     *     não há senha cadastrada
     */
    public synchronized boolean enter(char[] password, String by) {
        try {
            String stored = read();
            if (stored == null || !MasterPassword.matches(password, stored)) {
                log.warn("OPPRESSOR MODE recusado: senha mestre incorreta ({})", by);
                return false;
            }
            if (!active) {
                active = true;
                since = clock.instant();
                trigger = by;
                // Em WARN de propósito: o diário do sistema tem de mostrar
                // exatamente quando o motor de permissão saiu do caminho.
                log.warn("OPPRESSOR MODE ativo desde {} ({}): toda ação é liberada sem avaliação", since, by);
                events.accept(true, Map.of("since", since.toString(), "trigger", by));
            }
            return true;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** Sai do modo. Volta imediatamente ao comportamento normal. */
    public synchronized Map<String, Object> exit(String by) {
        if (active) {
            active = false;
            since = null;
            trigger = null;
            log.warn("OPPRESSOR MODE encerrado ({}): motor de permissão de volta", by);
            events.accept(false, Map.of("by", by == null ? "desconhecido" : by));
        }
        return status();
    }

    /**
     * Cadastra ou troca a senha mestre.
     *
     * <p>Trocar exige a atual; cadastrar a primeira, não — não há o que
     * conferir contra. Não mexe no estado do modo: trocar a senha com o modo
     * ativo não o derruba.
     *
     * @return {@code true} se gravou
     */
    public synchronized boolean password(char[] current, char[] next) {
        try {
            String stored = read();
            if (stored != null && !MasterPassword.matches(current, stored)) {
                log.warn("troca da senha mestre recusada: a senha atual não confere");
                return false;
            }
            write(MasterPassword.derive(next));
            log.warn("senha mestre do OPPRESSOR MODE {}", stored == null ? "cadastrada" : "trocada");
            return true;
        } finally {
            if (current != null) {
                Arrays.fill(current, '\0');
            }
            if (next != null) {
                Arrays.fill(next, '\0');
            }
        }
    }

    private String read() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            // Ilegível é "não confere": na dúvida o modo não abre.
            log.error("senha mestre ilegível em {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void write(String derived) {
        try {
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(part, derived);
            trimPermissions(part);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("senha mestre não gravada em " + file, e);
        }
    }

    /** Só o dono lê. Em sistema sem POSIX a chamada não existe, e aí o aviso fica no log. */
    private static void trimPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, ONLY_OWNER);
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("permissões de {} não restringidas: {}", path, e.toString());
        }
    }
}
