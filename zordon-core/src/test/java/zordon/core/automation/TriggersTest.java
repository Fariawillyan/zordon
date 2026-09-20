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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class TriggersTest {
    @Test @AcceptanceCriteria("SPEC-025/CA-2")
    void recuperacaoRespeitaSkipOnceAllETetoDeDez() {
        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        for (AutomationSpec.CatchUp catchUp : AutomationSpec.CatchUp.values()) {
            var trigger = new AutomationSpec.Interval(Duration.ofMinutes(1), catchUp);
            var state = new TriggerState();
            assertThat(state.due(trigger, now.minusSeconds(3600), now, 1)).hasSize(switch (catchUp) {
                case SKIP -> 0; case ONCE -> 1; case ALL -> 10;
            });
            assertThat(state.due(trigger, now, now.plusSeconds(5000), 2)).isEmpty();
            assertThat(state.due(trigger, now, now.minusSeconds(5000), Duration.ofMinutes(1).toNanos() + 1)).hasSize(1);
        }
    }

    @Test @AcceptanceCriteria("SPEC-025/CA-4")
    void condicaoExigeDuracaoRearmeEJanelaDeSilencio() {
        var condition = new AutomationSpec.Condition("cpu", 90, 75, Duration.ofSeconds(10), Duration.ofMinutes(15));
        TriggerState state = new TriggerState();
        assertThat(state.condition(condition, 95, 0)).isFalse();
        assertThat(state.condition(condition, 95, Duration.ofSeconds(9).toNanos())).isFalse();
        assertThat(state.condition(condition, 95, Duration.ofSeconds(10).toNanos())).isTrue();
        assertThat(state.condition(condition, 95, Duration.ofMinutes(20).toNanos())).isFalse();
        assertThat(state.condition(condition, 70, Duration.ofMinutes(21).toNanos())).isFalse();
        assertThat(state.condition(condition, 95, Duration.ofMinutes(22).toNanos())).isFalse();
        assertThat(state.condition(condition, 95, Duration.ofMinutes(22).plusSeconds(10).toNanos())).isTrue();
        assertThat(state.condition(condition, 70, Duration.ofMinutes(23).toNanos())).isFalse();
        assertThat(state.condition(condition, 95, Duration.ofMinutes(24).toNanos())).isFalse();
        assertThat(state.condition(condition, 95, Duration.ofMinutes(25).toNanos())).isFalse();
        assertThat(state.condition(condition, Double.NaN, Duration.ofMinutes(40).toNanos())).isFalse();
    }

    @Test void cronRespeitaFusoPassosDomingoEDiaOuSemana() {
        ZoneId zone = ZoneId.of("America/Sao_Paulo");
        assertThat(Cron.parse("*/10 9-17 * * 1-5").next(ZonedDateTime.of(2026, 9, 18, 17, 59, 0, 0, zone)))
                .isEqualTo(ZonedDateTime.of(2026, 9, 21, 9, 0, 0, 0, zone));
        assertThat(Cron.parse("0 9 * * 7").next(ZonedDateTime.of(2026, 9, 19, 12, 0, 0, 0, zone)).getDayOfWeek())
                .isEqualTo(java.time.DayOfWeek.SUNDAY);
        assertThatThrownBy(() -> Cron.parse("60 * * * *")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cron.parse("* * * *")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validaReferenciasReservadasDuracoesEEscopoDeEventos() {
        for (String reserved : List.of("trigger", "event")) {
            assertThatThrownBy(() -> AutomationSpec.fromMap(AutomationTest.raw("test", List.of(
                    AutomationTest.notifyStep(reserved, "Oi"))))).hasMessageContaining("id de passo");
        }
        assertThatThrownBy(() -> AutomationSpec.fromMap(Map.of("id", "test", "name", "Teste", "trigger",
                Map.of("type", "event", "event", "SECURITY_NOTIFICATION"), "step", List.of(AutomationTest.notifyStep("tell", "Oi")))))
                .hasMessageContaining("segurança");
        assertThatThrownBy(() -> AutomationSpec.fromMap(Map.of("id", "test", "name", "Teste", "trigger",
                Map.of("type", "interval", "every", "PT1M"), "limits", Map.of("timeout", "PT-1S"),
                "step", List.of(AutomationTest.notifyStep("tell", "Oi"))))).hasMessageContaining("positiva");
        assertThat(Expressions.when("missing.status != 200", Map.of())).isFalse();
        assertThat(Expressions.render(Map.of("nested", List.of(Map.of("message", "{{event.value}}"))),
                Map.of("event", Map.of("value", "$x\\data"))))
                .isEqualTo(Map.of("nested", List.of(Map.of("message", "$x\\data"))));
    }
}
