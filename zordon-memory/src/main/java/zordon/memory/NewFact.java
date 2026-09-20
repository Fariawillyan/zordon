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

import java.time.Instant;
import java.util.Objects;

/**
 * O que se pede para gravar.
 *
 * @param corrects marca como substituídos os fatos ativos do mesmo tipo e assunto
 */
public record NewFact(
        FactKind kind,
        String subject,
        String content,
        double confidence,
        Instant observedAt,
        Instant expiresAt,
        String provenance,
        String source,
        boolean corrects) {

    public static final int MAX_CONTENT = 300;
    public static final int MAX_SUBJECT = 80;

    public NewFact {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(source, "source");
        if (provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("fato sem procedência não grava (Memória §5)");
        }
        subject = subject == null ? "" : subject.strip();
        content = content == null ? "" : content.strip();
        if (subject.isEmpty() || content.isEmpty()) {
            throw new IllegalArgumentException("fato precisa de assunto e conteúdo");
        }
        if (content.length() > MAX_CONTENT || subject.length() > MAX_SUBJECT) {
            throw new IllegalArgumentException("fato longo demais");
        }
        if (!(confidence >= 0 && confidence <= 1)) {
            throw new IllegalArgumentException("confiança fora de 0 a 1: " + confidence);
        }
    }
}
