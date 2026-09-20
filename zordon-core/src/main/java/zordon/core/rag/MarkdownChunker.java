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
package zordon.core.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import zordon.api.trace.Spec;
import zordon.memory.KnowledgeStore;

/**
 * Markdown em pedaços por cabeçalho (SPEC-028). Cada pedaço carrega o caminho de
 * cabeçalhos, que é o que vira citação — "overview.md › 3. Componentes" responde
 * "onde isso está escrito?" sem o usuário abrir o arquivo.
 */
@Spec("SPEC-028")
public final class MarkdownChunker {

    static final int MAX_CHARS = 1_200;

    private MarkdownChunker() {}

    /** O front matter, quando existe: {@code document}, {@code module}, {@code securityLevel}… */
    public static Map<String, String> frontMatter(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!text.startsWith("---")) {
            return out;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return out;
        }
        for (String line : text.substring(3, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith(" ")) {
                out.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        return out;
    }

    public static List<KnowledgeStore.Chunk> chunks(String fileName, String text) {
        Map<String, String> front = frontMatter(text);
        String level = front.getOrDefault("securityLevel", "internal");
        String body = text;
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            body = end < 0 ? text : text.substring(end + 4);
        }
        List<KnowledgeStore.Chunk> out = new ArrayList<>();
        List<String> path = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String heading = fileName;
        boolean fenced = false;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            if (!fenced && line.startsWith("#")) {
                flush(out, heading, buffer, level);
                int depth = 0;
                while (depth < line.length() && line.charAt(depth) == '#') {
                    depth++;
                }
                while (path.size() >= depth) {
                    path.removeLast();
                }
                while (path.size() < depth - 1) {
                    path.add("");
                }
                path.add(line.substring(depth).strip());
                heading = fileName + path.stream().filter(part -> !part.isBlank())
                        .map(part -> " › " + part).reduce("", String::concat);
                continue;
            }
            if (buffer.length() + line.length() + 1 > MAX_CHARS && !fenced && line.isBlank()) {
                flush(out, heading, buffer, level);   // quebra em parágrafo, nunca no meio da frase
                continue;
            }
            buffer.append(line).append('\n');
        }
        flush(out, heading, buffer, level);
        return List.copyOf(out);
    }

    private static void flush(List<KnowledgeStore.Chunk> out, String heading, StringBuilder buffer, String level) {
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (!text.isBlank()) {
            out.add(new KnowledgeStore.Chunk(heading, text, level));
        }
    }
}
