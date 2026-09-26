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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Uma linha da auditoria e o hash dela.
 *
 * <p>{@code hash = sha256(prev_hash, separador, campos canônicos)}. A primeira
 * linha encadeia num {@code prev_hash} de 64 zeros.
 */
record AuditRow(String ts, String callId, String turnId, String actor, String origin, String tool, String args,
        String risk, String decision, String decidedBy, String status, Long durationMs, String summary,
        String error) {

    static final String GENESIS = "0".repeat(64);
    static final String COLUMNS =
            "ts, call_id, turn_id, actor, origin, tool, args_json, risk, decision, decided_by, status, "
                    + "duration_ms, result_summary, error";
    /** Separa campos no texto canônico: não aparece em JSON nem em texto digitado. */
    private static final String FIELD = String.valueOf((char) 31);
    private static final ObjectMapper json = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** A intenção, com argumentos e motivo já redigidos. */
    static AuditRow intent(String ts, AuditLog.Entry entry, Redactor redactor) {
        return new AuditRow(ts, entry.callId(), entry.turnId(), entry.principal().actor(),
                entry.principal().origin().wire(), entry.tool(), toJson(redactor.redact(entry.args())),
                entry.decision().risk().wire(), entry.decision().wire(), entry.decidedBy(),
                AuditLog.Status.STARTED.wire(), null, redactor.redact(entry.decision().reason()), null);
    }

    /** O desfecho desta intenção: mesma ação e decisão, novo estado. */
    AuditRow outcome(String at, AuditLog.Completion completion, Redactor redactor) {
        return new AuditRow(at, callId, turnId, actor, origin, tool, args, risk, decision, decidedBy,
                completion.status().wire(), completion.took() == null ? null : completion.took().toMillis(),
                redactor.redact(completion.summary()), redactor.redact(completion.error()));
    }

    /** Os valores na ordem de {@link #COLUMNS}. */
    Object[] values() {
        return new Object[] {ts, callId, turnId, actor, origin, tool, args, risk, decision, decidedBy, status,
                durationMs, summary, error};
    }

    String hash(String prev) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prev.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 30);
            sha.update(canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String canonical() {
        List<String> parts = new ArrayList<>();
        for (Object value : values()) {
            parts.add(value == null ? "" : value.toString());
        }
        return String.join(FIELD, parts);
    }

    private static String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
