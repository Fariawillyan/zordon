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
package zordon.desktop.shell;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * O que um turno concluído custou e quem o atendeu — o que o inspector mostra em
 * "Execução atual" e o que o rodapé da resposta resume.
 */
public record TurnSummary(
        String provider,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        BigDecimal costUsd,
        long latencyMs,
        boolean estimated,
        String fallbackFrom,
        boolean localRoute) {

    /** Lê o {@code AI_RESPONSE} final. Fragmentos e eventos sem consumo não viram resumo. */
    public static Optional<TurnSummary> fromResponse(Map<String, Object> payload) {
        if (!Boolean.TRUE.equals(payload.get("done")) || !(payload.get("usage") instanceof Map<?, ?> usage)) {
            return Optional.empty();
        }
        boolean local = payload.get("route") instanceof String;
        return Optional.of(new TurnSummary(
                text(payload.get("provider"), local ? "rota local" : "?"),
                text(payload.get("model"), local ? "sem modelo" : "?"),
                number(usage.get("inputTokens")),
                number(usage.get("outputTokens")),
                number(usage.get("cacheReadTokens")),
                decimal(payload.get("costUsd")),
                number(payload.get("latencyMs")),
                Boolean.TRUE.equals(usage.get("estimated")),
                payload.get("fallbackFrom") instanceof String from ? from : null,
                local));
    }

    /**
     * Rodapé da resposta. "≈" quando o provider não informou o consumo:
     * estimativa exibida como medida é mentira com cara de número.
     */
    public String footer() {
        if (localRoute) {
            return "resposta local · sem custo";
        }
        String approx = estimated ? "≈" : "";
        String fallback = fallbackFrom == null ? "" : " · reserva no lugar de " + fallbackFrom;
        return "%s · %s · %s%d tok entrada · %s%d tok saída · %d de cache · %s%s".formatted(
                provider, model, approx, inputTokens, approx, outputTokens, cacheReadTokens, cost(costUsd), fallback);
    }

    public static String cost(BigDecimal usd) {
        return "US$ " + usd.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String text(Object value, String absent) {
        return value instanceof String string && !string.isBlank() ? string : absent;
    }

    private static long number(Object value) {
        return value instanceof Number typed ? typed.longValue() : 0L;
    }

    private static BigDecimal decimal(Object value) {
        try {
            return value instanceof String text ? new BigDecimal(text) : BigDecimal.ZERO;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
