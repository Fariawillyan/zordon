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
package zordon.core.automation;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Os campos de uma automação como vêm do TOML ou do JSON: texto e duração ISO-8601. */
final class SpecFields {

    private SpecFields() {}

    static String text(Map<String, Object> raw, String key) {
        return raw.get(key) instanceof String value ? value : null;
    }

    static Duration duration(Map<String, Object> raw, String key, Duration fallback) {
        if (!(raw.get(key) instanceof String value)) {
            return fallback;
        }
        try {
            Duration parsed = Duration.parse(value);
            if (parsed.isNegative() || parsed.isZero()) {
                throw new IllegalArgumentException(key + " deve ser positiva");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " deve ser uma duração ISO-8601, como PT10M");
        }
    }
}
