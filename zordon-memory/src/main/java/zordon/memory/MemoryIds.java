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

import java.security.SecureRandom;
import java.time.Instant;

/** IDs opacos dos fatos, sem depender do store. */
final class MemoryIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private MemoryIds() {}

    static String fact(Instant now) {
        return "f_" + Long.toString(now.toEpochMilli(), 36) + Long.toString(RANDOM.nextLong() & 0xffffffL, 36);
    }
}
