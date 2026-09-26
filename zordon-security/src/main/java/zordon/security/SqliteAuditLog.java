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
package zordon.security;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import zordon.api.trace.Spec;

/**
 * A auditoria em SQLite (SPEC-014): append-only com cadeia de hash.
 *
 * <p>A tabela e os triggers ficam em {@link AuditStore}; a linha e o hash, em
 * {@link AuditRow}; a conferência da cadeia, em {@link AuditChain}. Esta classe
 * redige o que entra e serializa o acesso.
 */
@Spec("SPEC-014")
public final class SqliteAuditLog implements AuditLog {

    private final AuditStore store;
    private final Redactor redactor;
    private final Clock clock;

    public SqliteAuditLog(Path file, Redactor redactor, Clock clock) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = AuditStore.open(file);
    }

    @Override
    public synchronized long begin(Entry entry) {
        return store.append(AuditRow.intent(clock.instant().toString(), entry, redactor));
    }

    @Override
    public synchronized void complete(String callId, Completion completion) {
        AuditRow intent = store.started(callId);
        store.append(intent.outcome(clock.instant().toString(), completion, redactor));
    }

    @Override
    public synchronized Verification verify(int lastN) {
        return store.verify(lastN);
    }

    @Override
    public synchronized long entries() {
        return store.entries();
    }

    @Override
    public synchronized void close() {
        store.close();
    }
}
