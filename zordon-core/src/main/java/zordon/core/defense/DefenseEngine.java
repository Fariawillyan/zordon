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
package zordon.core.defense;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.security.Severity;
import zordon.api.trace.Spec;
import zordon.core.event.ZordonEventBus;
import zordon.core.notify.NotificationCenter;
import zordon.defense.CircuitBreakers;
import zordon.defense.Finding;
import zordon.defense.SecurityEvent;
import zordon.memory.SecurityEventStore;

/**
 * Do achado para a resposta (SPEC-027): ordenada, reversível e mínima. Playbook é
 * tabela, ação é código — a defesa não pergunta a modelo nenhum o que fazer.
 */
@Spec("SPEC-027")
public final class DefenseEngine {

    /** O que a resposta pode fazer sozinha. Tudo aqui é reversível (Defesa §5). */
    public interface Actions {
        /** Desconecta o servidor MCP e tira as ferramentas dele do registro. */
        boolean isolateMcp(String server);

        /** Cancela execuções do agente. Cancelar, nunca matar. */
        boolean cancelAgent(String agent);

        /** Só leitura, até o usuário retomar na tela. */
        void lockdown(String reason);
    }

    private static final Logger log = LoggerFactory.getLogger(DefenseEngine.class);

    private final CircuitBreakers breakers;
    private final SecurityEventStore events;
    private final NotificationCenter notifications;
    private final ZordonEventBus bus;
    private final Actions actions;
    private final Clock clock;

    public DefenseEngine(CircuitBreakers breakers, SecurityEventStore events, NotificationCenter notifications,
            ZordonEventBus bus, Actions actions, Clock clock) {
        this.breakers = breakers;
        this.events = events;
        this.notifications = notifications;
        this.bus = bus;
        this.actions = actions;
        this.clock = clock;
    }

    /** O playbook do achado. Chamado depois da SPEC-026 gravar e avisar. */
    public void respond(Finding finding, String userMessageId) {
        String subject = finding.subject().toString();
        String detector = finding.detector();
        boolean critical = finding.severity() == Severity.CRITICAL;
        if (detector.contains("integrity.audit-chain") || detector.contains("integrity.self")) {
            act(finding, userMessageId, "lockdown", () -> {
                actions.lockdown("integridade: " + finding.title());
                return true;
            });
            return;
        }
        if (detector.contains("ai.mcp-drift") && "mcp".equals(finding.subject().kind())) {
            act(finding, userMessageId, "isolar o servidor MCP", () -> {
                boolean isolated = actions.isolateMcp(finding.subject().id());
                breakers.open(subject, finding.title(), finding.id());
                return isolated;
            });
            return;
        }
        boolean tamper = detector.contains("ai.capability-violation") || detector.contains("ai.policy-tamper");
        boolean agentAbuse = detector.contains("ai.permission-probing") || detector.contains("ai.agent-loop")
                || detector.contains("ai.exfiltration");
        if (critical && tamper || agentAbuse && "agent".equals(finding.subject().kind())) {
            act(finding, userMessageId, "abrir o disjuntor", () -> {
                breakers.open(subject, finding.title(), finding.id());
                if ("agent".equals(finding.subject().kind())) {
                    actions.cancelAgent(finding.subject().id());
                }
                return true;
            });
            return;
        }
        // Sem playbook: fica registrado como observado, que é o que de fato aconteceu.
        record(finding, new Response("nenhuma", SecurityEvent.NONE, "OBSERVED", "POLICY", false), null);
    }

    private void act(Finding finding, String userMessageId, String proposed, java.util.function.BooleanSupplier run) {
        if (userMessageId == null || userMessageId.isBlank()) {
            // Sem aviso entregue não há contenção: a invariante do SecurityEvent existe por isso.
            log.warn("defesa não agiu em {}: nenhuma mensagem foi entregue ao usuário", finding.subject());
            record(finding, new Response(proposed, SecurityEvent.NONE, "OBSERVED", "DENIED", false), null);
            return;
        }
        boolean ok;
        try {
            ok = run.getAsBoolean();
        } catch (RuntimeException e) {
            log.warn("resposta '{}' falhou em {}: {}", proposed, finding.subject(), e.toString());
            ok = false;
        }
        log.warn("defesa: {} em {} ({})", proposed, finding.subject(), ok ? "contido" : "falhou");
        record(finding, new Response(proposed, proposed, ok ? "CONTAINED" : "FAILED", "AUTO_CONTAINMENT", true), userMessageId);
    }

    /** O desfecho de uma resposta da defesa: o que foi proposto, o que rodou e como. */
    private record Response(String proposed, String executed, String outcome, String authorization, boolean rollback) {}

    private void record(Finding finding, Response response, String userMessageId) {
        SecurityEvent event = new SecurityEvent("sec-" + UUID.randomUUID(), clock.instant(), finding.severity(),
                finding.detector(), finding.subject().toString(), finding.id(), response.proposed(),
                response.executed(), response.outcome(), response.authorization(), response.rollback(), userMessageId);
        events.securityEvent(new SecurityEventStore.SecurityEventRow(event.id(), event.ts(),
                event.severity().wire(), event.detector(), event.subject(), event.findingId(), event.proposed(),
                event.executed(), event.outcome(), event.authorization(), event.rollbackAvailable(),
                event.userMessageId()));
        if (!SecurityEvent.NONE.equals(event.executed())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", event.id());
            payload.put("subject", event.subject());
            payload.put("executed", event.executed());
            payload.put("outcome", event.outcome());
            payload.put("reversible", event.rollbackAvailable());
            bus.publish(EventType.SECURITY_ACTION_TAKEN, payload);
            bus.publish(EventType.CIRCUIT_BREAKER_OPENED, Map.of("subject", event.subject(), "reason",
                    finding.title(), "findingId", finding.id()));
        }
    }

    /** Liberação pelo usuário: supervisionado ou fechado. */
    public Optional<String> release(String subject, String mode) {
        Optional<CircuitBreakers.State> state = breakers.release(subject, mode);
        state.ifPresent(value -> {
            bus.publish(EventType.CIRCUIT_BREAKER_CLOSED, Map.of("subject", subject, "by", "user", "state",
                    value.name().toLowerCase(java.util.Locale.ROOT)));
            notifications.publish(notifications.message(new NotificationCenter.MessageFields(Severity.INFO, "SECURITY",
                    "Disjuntor liberado: " + subject,
                    "Você liberou " + subject + " como " + mode + ".",
                    "Liberar é decisão do dono: a defesa nunca fecha um disjuntor sozinha.",
                    "liberação na tela (SPEC-027)",
                    "O sujeito voltou a poder agir" + (value == CircuitBreakers.State.HALF_OPEN
                            ? ", com cada ação confirmada na tela." : "."),
                    subject, true, value.name().toLowerCase(java.util.Locale.ROOT),
                    List.of("Acompanhar na tela de Segurança"))));
        });
        return state.map(value -> value.name().toLowerCase(java.util.Locale.ROOT));
    }

    public List<Map<String, Object>> breakers() {
        return breakers.wire();
    }

    public List<Map<String, Object>> events(int limit) {
        return events.securityEvents(limit);
    }
}
