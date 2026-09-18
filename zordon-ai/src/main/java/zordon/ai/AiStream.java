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

import java.util.concurrent.CompletableFuture;

/**
 * Handle de um streaming em andamento.
 *
 * <p>Existe em vez de um {@code Stream<Token>} por quatro motivos: expressa
 * cancelamento de verdade (aborta o HTTP, não só para de ler), expressa erro no
 * meio, carrega tipos diferentes no mesmo fluxo e é push como a rede é
 * ([Interfaces §2](../../../../docs/api/core-interfaces.md#2-aiprovider)).
 */
public interface AiStream extends AutoCloseable {

    /** Aborta a requisição. O resultado completa com {@link StopReason#CANCELLED}. */
    void cancel();

    /** Completa no fim do fluxo, com o conteúdo acumulado, uso e custo. */
    CompletableFuture<AiResponse> result();

    @Override
    default void close() {
        cancel();
    }
}
