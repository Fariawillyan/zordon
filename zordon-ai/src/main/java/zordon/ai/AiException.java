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
package zordon.ai;

import java.time.Duration;
import java.util.Optional;

/**
 * Falha ao falar com um provider.
 *
 * <p>{@code retryable} é explícito para a UI poder decidir se oferece "tentar de
 * novo" sem adivinhar pelo texto da mensagem.
 */
public class AiException extends RuntimeException {

    /** Categoria da falha. É o que o núcleo traduz para o {@code kind} do ZWP. */
    public enum Kind {
        UNAVAILABLE,
        RATE_LIMITED,
        TIMEOUT,
        INVALID_REQUEST,
        NO_CREDENTIALS,
        /** Conta sem crédito ou cota esgotada. Tentar de novo não resolve; pagar resolve. */
        QUOTA_EXHAUSTED,
        CANCELLED
    }

    private final Kind kind;
    private final transient Duration retryAfter;

    public AiException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public AiException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null);
    }

    public AiException(Kind kind, String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isRetryable() {
        return kind == Kind.UNAVAILABLE || kind == Kind.RATE_LIMITED || kind == Kind.TIMEOUT;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
