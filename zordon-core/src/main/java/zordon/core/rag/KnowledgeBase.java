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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.memory.KnowledgeStore;

/**
 * A documentação do projeto como índice consultável (SPEC-028). Indexa o que mudou,
 * responde com citação, e diz quando o índice está velho em vez de fingir que não
 * está.
 */
@Spec("SPEC-028")
public final class KnowledgeBase {

    /** Tetos de contexto: cabe no orçamento de 8.000 tokens do M8. */
    public static final int MAX_HITS = 8;
    public static final int MAX_CHARS = 6_000;
    static final String STALE = "[índice velho: %d arquivo(s) mudaram desde a última indexação]";

    public record Indexed(int files, int chunks, long tookMs) {}

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final KnowledgeStore store;
    private final List<Path> roots;
    private volatile List<String> unreadable = List.of();

    public KnowledgeBase(KnowledgeStore store, List<Path> roots) {
        this.store = store;
        this.roots = roots.stream().map(Path::toAbsolutePath).toList();
    }

    /** As raízes do {@code [rag] roots}; sem elas, o {@code docs/} do repositório, se existir. */
    public static List<Path> defaultRoots(Path configToml, Path repo) {
        List<Path> configured = new ArrayList<>();
        if (Files.exists(configToml)) {
            try {
                org.tomlj.TomlArray array = org.tomlj.Toml.parse(configToml).getArray("rag.roots");
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        configured.add(Path.of(array.getString(i).replaceFirst("^~",
                                System.getProperty("user.home"))));
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("[rag] roots ilegível: {}", e.getMessage());
            }
        }
        if (configured.isEmpty() && repo != null && Files.isDirectory(repo.resolve("docs"))) {
            configured.add(repo.resolve("docs"));
        }
        return List.copyOf(configured);
    }

    /** Indexa o que mudou. Arquivo igual não é reescrito; o que sumiu sai. */
    public synchronized Indexed reindex() {
        long started = System.nanoTime();
        List<String> seen = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int chunks = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                failed.add(root + ": pasta inexistente");
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".md")).sorted().toList()) {
                    String path = file.toString();
                    seen.add(path);
                    try {
                        String text = Files.readString(file, StandardCharsets.UTF_8);
                        chunks += store.indexDocument(path, hash(text),
                                MarkdownChunker.chunks(file.getFileName().toString(), text));
                    } catch (IOException | RuntimeException e) {
                        failed.add(path + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                failed.add(root + ": " + e.getMessage());
            }
        }
        int removed = store.forgetDocumentsOutside(seen);
        unreadable = List.copyOf(failed);
        long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
        log.info("conhecimento: {} arquivos, {} pedaços novos, {} removidos, em {} ms", seen.size(), chunks, removed,
                took);
        return new Indexed(seen.size(), chunks, took);
    }

    /** Quantos arquivos mudaram desde a indexação: o índice velho é dito, não escondido. */
    public int staleFiles() {
        Map<String, String> indexed = store.indexedDocuments();
        int stale = 0;
        for (Map.Entry<String, String> entry : indexed.entrySet()) {
            try {
                if (!hash(Files.readString(Path.of(entry.getKey()), StandardCharsets.UTF_8)).equals(entry.getValue())) {
                    stale++;
                }
            } catch (IOException | RuntimeException e) {
                stale++;   // sumiu ou ficou ilegível: também é índice velho
            }
        }
        return stale;
    }

    /** A busca com citação. O texto volta cortado no teto de contexto. */
    public List<KnowledgeStore.Hit> search(String query, int limit) {
        List<KnowledgeStore.Hit> hits = store.searchDocs(query, Math.min(limit <= 0 ? MAX_HITS : limit, MAX_HITS));
        List<KnowledgeStore.Hit> out = new ArrayList<>();
        int budget = MAX_CHARS;
        for (KnowledgeStore.Hit hit : hits) {
            if (budget <= 0) {
                break;
            }
            String text = hit.text().length() > budget ? hit.text().substring(0, budget) + "…" : hit.text();
            budget -= text.length();
            out.add(new KnowledgeStore.Hit(hit.path(), hit.heading(), text, hit.score()));
        }
        return List.copyOf(out);
    }

    /** O texto que vai ao modelo: cada linha citando arquivo e seção. */
    public String answerContext(String query, int limit) {
        List<KnowledgeStore.Hit> hits = search(query, limit);
        if (hits.isEmpty()) {
            return String.valueOf(store.knowledgeStats().get("chunks")).equals("0")
                    ? "Não há índice de documentação ainda. Reindexe pela tela (rag.reindex)."
                    : "Nada na documentação sobre isso.";
        }
        StringBuilder out = new StringBuilder();
        int stale = staleFiles();
        if (stale > 0) {
            out.append(String.format(STALE, stale)).append('\n');
        }
        hits.forEach(hit -> out.append("— ").append(hit.heading()).append(" (").append(shortPath(hit.path()))
                .append(")\n").append(hit.text()).append("\n\n"));
        return out.toString().strip();
    }

    public static String shortPath(String path) {
        int docs = path.indexOf("/docs/");
        return docs >= 0 ? path.substring(docs + 1) : path;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>(store.knowledgeStats());
        out.put("roots", roots.stream().map(Path::toString).toList());
        out.put("staleFiles", staleFiles());
        if (!unreadable.isEmpty()) {
            out.put("problems", unreadable);
        }
        return out;
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
