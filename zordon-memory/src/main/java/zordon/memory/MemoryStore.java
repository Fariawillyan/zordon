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
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A memória do Zordon (SPEC-021, Interfaces §8). O caminho para outro banco fica
 * aqui: nenhum SQL de memória sai deste módulo.
 */
public interface MemoryStore extends AutoCloseable {

    // conversa: registro -------------------------------------------------

    void session(String sessionId, String title, Instant startedAt);

    void message(String sessionId, String turnId, String role, String content, Instant ts);

    /** As últimas {@code limit} linhas da sessão, em ordem cronológica. */
    List<StoredLine> history(String sessionId, int limit);

    /** As linhas de um turno, em ordem. */
    List<StoredLine> turn(String turnId);

    // fatos: conhecimento destilado ---------------------------------------

    /** Grava, ou devolve o fato ativo igual que já existe. */
    Fact remember(NewFact fact);

    List<MemoryHit> search(RecallQuery query);

    /** Fatos ativos, do mais novo para o mais antigo. Tipo e assunto opcionais. */
    List<Fact> facts(FactKind kind, String subject, int limit);

    /** Fatos usados num contexto: sobe o contador de acessos. */
    void touched(Collection<String> factIds);

    /** Apaga de verdade, inclusive do índice (Memória §8). */
    boolean forget(String factId);

    int forgetSubject(String subject);

    // destilação ----------------------------------------------------------

    record DistillJob(String turnId, String sessionId, boolean tainted, int attempts) {}

    void enqueueDistill(String turnId, String sessionId, boolean tainted, Instant now);

    List<DistillJob> dueDistill(Instant now, int limit);

    void distilled(String turnId);

    void distillFailed(String turnId, String error, Instant nextAt, boolean giveUp);

    // diagnóstico ---------------------------------------------------------

    Map<String, Object> stats();

    @Override
    void close();
}
