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
package zordon.core.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.StartId;
import zordon.core.event.ZordonEventBus;

class ActivityServiceTest {

    @AcceptanceCriteria("SPEC-012/CA-5")
    @Test
    void aNarracaoTrazTextoPrioridadeECategoriaSemDetalheTecnico() {
        ZordonEventBus bus = new ZordonEventBus(StartId.generate());
        List<EventEnvelope> published = new CopyOnWriteArrayList<>();
        List<Narration> spoken = new CopyOnWriteArrayList<>();
        Clock clock = Clock.fixed(Instant.parse("2026-09-18T18:00:00Z"), ZoneOffset.UTC);
        ActivityService service = new ActivityService(bus, spoken::add, clock, null);
        bus.subscribe("teste", java.util.Set.of("voice"), zordon.core.event.QueuePolicy.dropOldest(64), published::add);

        service.accept(new EventEnvelope(1, clock.instant(), EventType.USER_COMMAND,
                Map.of("turnId", "t_18f3a", "sessionId", "s_1", "source", "voice", "text", "que horas são")));
        service.accept(new EventEnvelope(2, clock.instant(), EventType.AI_ERROR,
                Map.of("turnId", "t_18f3a", "kind", "NO_CREDENTIALS",
                        "message", "defina ANTHROPIC_API_KEY em ~/.zordon/secrets.env")));

        assertThat(spoken).hasSize(1);
        Map<String, Object> payload = spoken.getFirst().payload();
        assertThat(payload).containsOnlyKeys("text", "priority", "category")
                .containsEntry("priority", "high").containsEntry("category", "erro");
        assertThat(payload.get("text").toString())
                .doesNotContain("/").doesNotContain("t_18f3a").doesNotContain("ANTHROPIC_API_KEY");
        assertThat(service.state()).isEqualTo("error");
        bus.close();
    }
}
