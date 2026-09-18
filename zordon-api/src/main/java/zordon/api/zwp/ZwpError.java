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

/** Erro JSON-RPC com o significado de aplicação em {@code data.kind}. */
public record ZwpError(int code, String message, ZwpErrorKind kind, Map<String, Object> data) {

    /** Códigos de protocolo do JSON-RPC 2.0. */
    public static final int PARSE_ERROR = -32700;

    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /** Todo erro de aplicação usa este código; o significado vai em {@link #kind}. */
    public static final int APPLICATION_ERROR = -32001;

    public ZwpError {
        Objects.requireNonNull(message, "message");
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ZwpError of(ZwpErrorKind kind, String message) {
        return new ZwpError(APPLICATION_ERROR, message, Objects.requireNonNull(kind, "kind"), Map.of());
    }

    public static ZwpError of(ZwpErrorKind kind, String message, Map<String, Object> data) {
        return new ZwpError(APPLICATION_ERROR, message, Objects.requireNonNull(kind, "kind"), data);
    }

    public static ZwpError protocol(int code, String message) {
        return new ZwpError(code, message, null, Map.of());
    }
}
