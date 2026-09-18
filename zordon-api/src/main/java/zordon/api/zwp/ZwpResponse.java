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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resposta JSON-RPC. Ou {@code result} ou {@code error}, nunca os dois — o
 * construtor recusa as duas outras combinações em vez de deixar o consumidor
 * descobrir tarde.
 */
public record ZwpResponse(long id, Map<String, Object> result, ZwpError error) implements ZwpMessage {

    public ZwpResponse {
        boolean hasResult = result != null;
        boolean hasError = error != null;
        if (hasResult == hasError) {
            throw new IllegalArgumentException("resposta precisa de exatamente um entre result e error");
        }
        result = hasResult ? Map.copyOf(result) : null;
    }

    public static ZwpResponse ok(long id, Map<String, Object> result) {
        return new ZwpResponse(id, Objects.requireNonNull(result, "result"), null);
    }

    public static ZwpResponse failed(long id, ZwpError error) {
        return new ZwpResponse(id, null, Objects.requireNonNull(error, "error"));
    }

    public boolean isError() {
        return error != null;
    }

    public Optional<ZwpError> errorIfAny() {
        return Optional.ofNullable(error);
    }
}
