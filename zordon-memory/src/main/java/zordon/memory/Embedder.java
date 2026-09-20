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
package zordon.memory;

import java.util.List;

/**
 * A busca vetorial, quando houver um modelo local de embeddings (Memória §4). Até
 * lá o RRF funde só a lista lexical; este é o ponto de extensão.
 */
public interface Embedder {

    /** Os ids dos fatos mais próximos da consulta, do mais para o menos parecido. */
    List<String> nearest(String query, int limit);

    /** Avisado de cada fato gravado, para indexar. */
    default void indexed(Fact fact) {}

    /** Avisado de cada fato esquecido, para tirar do índice. */
    default void forgotten(String factId) {}
}
