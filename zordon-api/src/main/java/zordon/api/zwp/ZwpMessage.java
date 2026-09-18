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

/**
 * Uma das três formas do JSON-RPC 2.0 que o ZWP usa (docs/api/zwp-protocol.md §3).
 *
 * <p>O protocolo é simétrico: núcleo e cliente emitem as três, cada lado com o seu
 * espaço de {@code id}.
 */
public sealed interface ZwpMessage permits ZwpRequest, ZwpResponse, ZwpNotification {

    /** Versão do envelope JSON-RPC. O ZWP não admite outra. */
    String JSONRPC_VERSION = "2.0";
}
