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
package zordon.ai.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import zordon.ai.AiException;
import zordon.api.TokenUsage;

/** A saída do CLI: o JSON, a recusa dentro dele e o uso de tokens. */
final class CliOutput {

    private static final ObjectMapper json = new ObjectMapper();

    private CliOutput() {}

    /** A saída do CLI como JSON. O que não é JSON é falha do programa, não resposta. */
    static JsonNode parse(CliRunner.Result result) {
        JsonNode out;
        try {
            out = json.readTree(result.stdout().strip());
        } catch (Exception e) {
            out = null;
        }
        if (out == null || !out.isObject()) {
            String detail = result.stderr().lines().limit(3).reduce((a, b) -> a + " " + b).orElse("sem detalhes");
            throw new AiException(AiException.Kind.UNAVAILABLE,
                    "saída do claude não é JSON (código " + result.exitCode() + "): " + detail);
        }
        return out;
    }

    /** O CLI respondeu, mas recusando: cota, credencial ou outra coisa. */
    static void refuseIfError(JsonNode out, String text) {
        if (!out.path("is_error").asBoolean(false) && "success".equals(out.path("subtype").asText("success"))) {
            return;
        }
        String message = text.isBlank() ? out.path("subtype").asText("erro") : text;
        String lower = message.toLowerCase(Locale.ROOT);
        AiException.Kind kind = lower.contains("limit") ? AiException.Kind.QUOTA_EXHAUSTED
                : lower.contains("log") ? AiException.Kind.NO_CREDENTIALS : AiException.Kind.UNAVAILABLE;
        throw new AiException(kind, "o claude recusou: " + message);
    }

    static TokenUsage usage(JsonNode usage) {
        return new TokenUsage(usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0), usage.path("cache_read_input_tokens").asLong(0));
    }
}
