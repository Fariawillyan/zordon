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
 * Significado de um erro de aplicação, transportado em {@code error.data.kind}
 * (docs/api/zwp-protocol.md §9).
 *
 * <p>O código JSON-RPC de todos é o mesmo ({@link ZwpError#APPLICATION_ERROR}); é
 * este enum que o cliente usa para decidir o que fazer.
 */
public enum ZwpErrorKind {
    ERR_UNAUTHORIZED,
    ERR_PROTOCOL_UNSUPPORTED,
    ERR_BRIDGE_UNAVAILABLE,
    ERR_PERMISSION_DENIED,
    ERR_BUDGET_EXCEEDED,
    ERR_TOOL_FAILED,
    ERR_MCP_UNAVAILABLE,
    ERR_AI_UNAVAILABLE,
    ERR_RATE_LIMITED,
    ERR_INVALID_ARGUMENT,
    ERR_NOT_FOUND,
    ERR_CANCELLED,
    ERR_LOCKDOWN,
    ERR_CIRCUIT_OPEN,

    /**
     * Tentativa de alterar política, auditoria ou capacidade. Nunca acontece em uso
     * normal: quando acontece, é achado de segurança CRITICAL.
     */
    ERR_IMMUTABLE
}
