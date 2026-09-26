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
package zordon.ai.openai;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/** Quando vale tentar de novo, e quanto esperar antes. */
final class OpenAiRetry {

    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);

    private OpenAiRetry() {}

    /** 429 de limite e 5xx merecem outra tentativa; 429 de crédito não — pagar resolve, esperar não. */
    static boolean isRetryable(int status, String body) {
        boolean quota = body != null && body.toLowerCase(Locale.ROOT).contains("insufficient_quota");
        return (status == 429 && !quota) || status >= 500;
    }

    /** @return falso quando o pedido foi cancelado durante a espera */
    static boolean waitBeforeRetry(HttpResponse<?> response, int attempt, BooleanSupplier cancelled)
            throws InterruptedException {
        long waitMillis = response.headers().firstValue("retry-after")
                .map(OpenAiRetry::parseSeconds)
                .orElseGet(() -> (long) (500L * (1L << (attempt - 1)) * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble())));
        long deadline = System.nanoTime() + Duration.ofMillis(Math.min(waitMillis, MAX_RETRY_WAIT.toMillis())).toNanos();
        while (System.nanoTime() < deadline) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            Thread.sleep(25);
        }
        return true;
    }

    static String readBounded(InputStream body) throws IOException {
        try (body) {
            return new String(body.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static Long parseSeconds(String value) {
        try {
            return Math.max(0, Long.parseLong(value.strip())) * 1000;
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }
}
