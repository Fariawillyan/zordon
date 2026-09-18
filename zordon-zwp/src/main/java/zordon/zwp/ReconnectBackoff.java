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
package zordon.zwp;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Backoff de reconexão do ZWP §8: 250 ms, 500 ms, 1 s, 2 s, 5 s, 10 s (teto), com
 * jitter de ±20%.
 *
 * <p>O jitter não é enfeite: sem ele, desktop e host reconectam no mesmo
 * milissegundo depois de um reinício do núcleo.
 */
public final class ReconnectBackoff {

    private static final List<Duration> STEPS = List.of(
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10));

    private final RandomGenerator random;
    private int attempt;

    public ReconnectBackoff() {
        this(RandomGenerator.getDefault());
    }

    ReconnectBackoff(RandomGenerator random) {
        this.random = random;
    }

    public Duration nextDelay() {
        Duration base = STEPS.get(Math.min(attempt, STEPS.size() - 1));
        attempt++;
        double jitter = 0.8 + random.nextDouble() * 0.4;
        return Duration.ofMillis(Math.round(base.toMillis() * jitter));
    }

    public void reset() {
        attempt = 0;
    }

    public int attempts() {
        return attempt;
    }
}
