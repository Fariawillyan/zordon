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
package zordon.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.security.Severity;
import zordon.core.defense.DefenseService;
import zordon.core.event.ZordonEventBus;
import zordon.core.notify.NotificationCenter;
import zordon.core.permission.LockdownService;
import zordon.security.AuditLog;

/** Confere a cadeia da auditoria na inicialização; quebrada, é alerta crítico e lockdown (SPEC-014 §13). */
final class AuditCheck {

    private static final Logger log = LoggerFactory.getLogger(ZordonCore.class);

    private final AuditLog audit;
    private final ZordonEventBus bus;
    private final LockdownService lockdown;
    private final NotificationCenter notifications;
    private volatile Map<String, Object> state = Map.of("chain", "unverified");

    AuditCheck(AuditLog audit, ZordonEventBus bus, LockdownService lockdown, NotificationCenter notifications) {
        this.audit = audit;
        this.bus = bus;
        this.lockdown = lockdown;
        this.notifications = notifications;
    }

    Map<String, Object> state() {
        return state;
    }

    void verify(DefenseService defense) {
        AuditLog.Verification verification = audit.verify(1000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", audit.entries());
        result.put("chain", verification.ok() ? "ok" : "broken");
        result.put("checked", verification.checked());
        result.put("verifiedAt", Instant.now().toString());
        if (!verification.ok()) {
            result.put("firstBroken", verification.firstBroken());
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("severity", "critical");
            alert.put("message", "a cadeia da auditoria está quebrada a partir da linha "
                    + verification.firstBroken() + "; ações acima de GREEN ficam negadas até verificação");
            bus.publish(EventType.SYSTEM_ALERT, alert);
            log.error("auditoria: cadeia quebrada na linha {}", verification.firstBroken());
            defense.auditChainBroken("cadeia quebrada a partir da linha " + verification.firstBroken());
            lockdown.enter("a cadeia da auditoria está quebrada", "audit");
            notifications.publish(notifications.message(new NotificationCenter.MessageFields(
                    Severity.CRITICAL, "SECURITY",
                    "A auditoria foi alterada por fora",
                    "A cadeia de hash da auditoria não confere a partir da linha " + verification.firstBroken() + ".",
                    "Uma linha só muda assim se alguém mexer no arquivo audit.db fora do Zordon.",
                    "verificação da auditoria na inicialização do núcleo",
                    "O Zordon entrou em só leitura (lockdown).",
                    "~/.zordon/state/audit.db",
                    true,
                    "Só leitura: nenhuma ação acima de GREEN executa.",
                    List.of("Conferir o arquivo e retomar na tela", "Manter pausado"))));
        } else {
            log.info("auditoria: cadeia íntegra ({} linhas conferidas de {})", verification.checked(), audit.entries());
        }
        state = Map.copyOf(result);
    }
}
