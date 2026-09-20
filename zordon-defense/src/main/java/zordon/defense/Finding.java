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
package zordon.defense;

import java.time.Instant;
import java.util.List;
import zordon.api.security.Severity;

/**
 * O que a correlação concluiu. {@code rationale} é escrito por código a partir das
 * regras que dispararam: é o que o usuário lê para decidir (Defesa §4).
 */
public record Finding(String id, Severity severity, String detector, Subject subject, String title, String rationale,
        List<Signal> signals, int count, Instant firstSeen, Instant lastSeen) {

    public static final int MAX_SIGNALS = 100;

    public Finding {
        signals = List.copyOf(signals.size() > MAX_SIGNALS ? signals.subList(signals.size() - MAX_SIGNALS,
                signals.size()) : signals);
    }

    public double weight() {
        return signals.stream().mapToDouble(Signal::weight).sum();
    }
}
