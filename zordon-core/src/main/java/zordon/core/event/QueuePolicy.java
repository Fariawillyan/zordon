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
package zordon.core.event;

/**
 * O que fazer quando a fila de um assinante enche (ADR-0011).
 *
 * <p>Não existe {@code BLOCK_PRODUCER}: uma interface travada não pode atrasar a
 * execução de uma ferramenta.
 */
public record QueuePolicy(int capacity, Overflow overflow) {

    public enum Overflow {
        /** Descarta o mais antigo e marca o próximo evento com {@code gap}. */
        DROP_OLDEST,

        /** O último valor substitui o pendente do mesmo tipo. Para métricas e nível de áudio. */
        COALESCE,

        /**
         * Recusa a publicação e falha alto. Para {@code permission}, {@code security}
         * e {@code change}: descartar em silêncio é inaceitável nesses tópicos.
         */
        REJECT_PUBLISH
    }

    public QueuePolicy {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacidade precisa ser positiva");
        }
    }

    public static QueuePolicy dropOldest(int capacity) {
        return new QueuePolicy(capacity, Overflow.DROP_OLDEST);
    }

    public static QueuePolicy coalesce(int capacity) {
        return new QueuePolicy(capacity, Overflow.COALESCE);
    }

    public static QueuePolicy rejectPublish(int capacity) {
        return new QueuePolicy(capacity, Overflow.REJECT_PUBLISH);
    }
}
