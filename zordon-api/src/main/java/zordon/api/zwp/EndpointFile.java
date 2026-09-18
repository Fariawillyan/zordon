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
package zordon.api.zwp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Conteúdo de {@code .zordon/endpoint.json} (ADR-0006).
 *
 * <p>Resolve descoberta, autenticação e prova de co-localização de uma vez: só um
 * processo rodando como o mesmo usuário na mesma máquina consegue ler o token, e
 * um navegador não consegue nem ler o arquivo nem definir o cabeçalho.
 */
public record EndpointFile(
        int version,
        String startId,
        Instant startedAt,
        String networkingMode,
        List<String> endpoints,
        String token,
        ProtocolRange protocol,
        long pid) {

    public static final int CURRENT_VERSION = 1;

    public EndpointFile {
        Objects.requireNonNull(startId, "startId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(protocol, "protocol");
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoint.json sem nenhum endereço é indescobrível");
        }
    }
}
