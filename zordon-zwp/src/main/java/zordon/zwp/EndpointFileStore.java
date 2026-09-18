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
package zordon.zwp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import zordon.api.zwp.EndpointFile;

/**
 * Leitura e escrita de {@code endpoint.json} (ADR-0006).
 *
 * <p>A escrita é atômica: um cliente que leia o arquivo no meio de uma atualização
 * receberia um token truncado e falharia a autenticação sem explicação.
 */
public final class EndpointFileStore {

    public static final String FILE_NAME = "endpoint.json";

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void write(Path file, EndpointFile endpoint) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(endpoint));
            restrictToOwner(temporary);
            moveInto(temporary, file);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao publicar " + file, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("falha ao serializar endpoint.json", e);
        }
    }

    public Optional<EndpointFile> read(Path file) {
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readString(file), EndpointFile.class));
        } catch (IOException | IllegalArgumentException e) {
            // Arquivo pela metade ou de uma versão futura: tratar como ausente é
            // melhor do que subir com um token inválido.
            return Optional.empty();
        }
    }

    private void moveInto(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 9p (/mnt/c) não garante move atômico; o destino é o perfil do Windows
            // e a janela de inconsistência é de milissegundos, uma vez por boot.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException e) {
            // DrvFs não expõe permissões POSIX. No lado Windows a proteção é a ACL
            // do perfil do usuário, verificada pelo instalador.
        }
    }
}
