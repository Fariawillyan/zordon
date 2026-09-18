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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Parâmetros de {@code session.hello} (docs/api/zwp-protocol.md §2). */
public record HelloParams(
        ClientInfo client, ProtocolRange protocol, List<String> capabilities, ResumeRequest resume) {

    public HelloParams {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(protocol, "protocol");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    public static HelloParams of(ClientInfo client, List<String> capabilities) {
        return new HelloParams(client, ProtocolRange.exactly(ZwpProtocol.VERSION), capabilities, null);
    }

    /** Retomada é opcional: uma primeira conexão não tem o que retomar. */
    public Optional<ResumeRequest> resumeIfAny() {
        return Optional.ofNullable(resume);
    }
}
