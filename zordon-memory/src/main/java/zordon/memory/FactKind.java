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

/** Os tipos de fato (Memória §3). */
public enum FactKind {
    PREFERENCE,
    PROJECT,
    ENTITY,
    EVENT,
    PROCEDURE;

    /** Meia-vida da recência: preferência e procedimento não envelhecem. */
    java.time.Duration halfLife() {
        return switch (this) {
            case EVENT -> java.time.Duration.ofDays(90);
            case PREFERENCE, PROCEDURE -> null;
            case PROJECT, ENTITY -> java.time.Duration.ofDays(365);
        };
    }
}
