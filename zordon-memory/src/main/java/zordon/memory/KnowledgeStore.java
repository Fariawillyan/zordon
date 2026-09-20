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
import java.util.Map;

/** O índice da documentação (SPEC-028). É dado derivado: refeito a partir dos arquivos. */
public interface KnowledgeStore {

    /** Um pedaço de documento, já com o caminho de cabeçalhos. */
    record Chunk(String heading, String text, String level) {}

    record Hit(String path, String heading, String text, double score) {}

    /** @return quantos pedaços entraram; 0 quando o hash é o mesmo e nada foi reescrito */
    int indexDocument(String path, String hash, List<Chunk> chunks);

    /** Tira do índice o que não está mais na lista de arquivos vistos. */
    int forgetDocumentsOutside(List<String> paths);

    List<Hit> searchDocs(String query, int limit);

    Map<String, Object> knowledgeStats();

    /** O hash com que cada arquivo foi indexado. */
    Map<String, String> indexedDocuments();
}
