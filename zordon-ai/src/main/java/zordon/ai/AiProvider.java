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

import java.util.List;

/**
 * Contrato com um modelo de linguagem.
 *
 * <p>Toda implementação honra o mesmo contrato, inclusive cancelamento e erro: é o
 * que permite trocar de provider — inclusive para um local, sem rede — sem que o
 * núcleo saiba.
 */
public interface AiProvider {

    ProviderInfo info();

    /** Síncrono. Para roteamento, classificação e tarefas curtas. */
    AiResponse chat(AiRequest request) throws AiException;

    /**
     * Streaming. Devolve um handle cancelável; o conteúdo chega pelo listener.
     *
     * <p>Todo caminho de conversa usa este método: {@code maxTokens} alto sem
     * streaming estoura o tempo limite do HTTP antes de a resposta terminar.
     */
    AiStream stream(AiRequest request, AiStreamListener listener);

    /** Embeddings. Nem todo provider oferece — ver {@link ProviderInfo#capabilities()}. */
    default List<float[]> embed(List<String> texts) {
        throw new UnsupportedOperationException(info().id() + " não gera embeddings");
    }

    /** Contagem de tokens sem gastar geração. Usado para orçamento. */
    long countTokens(AiRequest request);
}
