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

import java.nio.file.Path;
import java.time.Clock;
import zordon.api.event.EventType;
import zordon.core.notify.NotificationCenter;
import zordon.core.permission.DesktopApprover;
import zordon.core.permission.LockdownService;
import zordon.core.permission.OppressorService;
import zordon.security.AuditLog;
import zordon.security.Gatekeeper;
import zordon.security.PermissionEngine;
import zordon.security.PermissionEngines;
import zordon.security.ProcessRunner;
import zordon.security.SqliteAuditLog;

/**
 * O núcleo de confiança (SPEC-014 a SPEC-017, SPEC-036): auditoria, permissão,
 * pedido na tela, notificações, lockdown e OPPRESSOR MODE. Existe antes da
 * primeira ação com efeito.
 */
final class TrustModule {

    private final AuditLog audit;
    private final DesktopApprover approver;
    private final NotificationCenter notifications;
    private final OppressorService oppressor;
    private final Gatekeeper gatekeeper;
    private final ProcessRunner runner;
    private final LockdownService lockdown;
    private final AuditCheck check;

    TrustModule(CoreBase base) {
        Path state = base.config().home().resolve("state");
        this.audit = new SqliteAuditLog(state.resolve("audit.db"), base.redactor(), Clock.systemUTC());
        this.approver = new DesktopApprover(base.server());
        PermissionEngine permissions = PermissionEngines.standard(base.settings().load().paths(),
                base.settings().load().validator(), base.redactor(), () -> approver);
        this.notifications = new NotificationCenter(state.resolve("notifications.db"), Clock.systemUTC(),
                message -> base.bus().publish(EventType.SECURITY_NOTIFICATION, message.payload()));
        // Antes do gatekeeper porque é ele quem consulta o modo a cada ação.
        this.oppressor = new OppressorService(state.resolve("oppressor.hash"), Clock.systemUTC(),
                (entered, payload) -> base.bus().publish(
                        entered ? EventType.OPPRESSOR_ENTERED : EventType.OPPRESSOR_EXITED, payload));
        this.gatekeeper = new Gatekeeper(permissions, audit, oppressor::active);
        this.runner = new ProcessRunner(base.settings().load().validator());
        this.lockdown = new LockdownService(state.resolve("lockdown.json"), Clock.systemUTC(),
                (entered, payload) -> base.bus().publish(
                        entered ? EventType.LOCKDOWN_ENTERED : EventType.LOCKDOWN_EXITED, payload));
        this.check = new AuditCheck(audit, base.bus(), lockdown, notifications);
    }

    AuditLog audit() {
        return audit;
    }

    DesktopApprover approver() {
        return approver;
    }

    NotificationCenter notifications() {
        return notifications;
    }

    OppressorService oppressor() {
        return oppressor;
    }

    Gatekeeper gatekeeper() {
        return gatekeeper;
    }

    ProcessRunner runner() {
        return runner;
    }

    LockdownService lockdown() {
        return lockdown;
    }

    AuditCheck check() {
        return check;
    }
}
