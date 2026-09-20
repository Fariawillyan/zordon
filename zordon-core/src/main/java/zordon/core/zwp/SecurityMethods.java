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
package zordon.core.zwp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.notify.NotificationCenter;
import zordon.core.permission.DesktopApprover;
import zordon.core.permission.LockdownService;

/**
 * Notificações, kill switch e o registro dos desktops que autorizam
 * (SPEC-015 §7).
 */
@Spec("SPEC-015")
public final class SecurityMethods {

    private final NotificationCenter notifications;
    private final LockdownService lockdown;
    private final DesktopApprover approver;
    private final Supplier<Map<String, Object>> audit;

    public SecurityMethods(NotificationCenter notifications, LockdownService lockdown, DesktopApprover approver,
            Supplier<Map<String, Object>> audit) {
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.lockdown = Objects.requireNonNull(lockdown, "lockdown");
        this.approver = Objects.requireNonNull(approver, "approver");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public void registerOn(ZwpServer server) {
        server.register("notify.pending", (session, params) -> Map.of("messages", notifications.pending()))
                .register("notify.acknowledge", (session, params) -> {
                    if (!(params.get("messageId") instanceof String id) || id.isBlank()) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "messageId é obrigatório");
                    }
                    return Map.of("acknowledged", notifications.acknowledge(id));
                })
                .register("security.lockdown", (session, params) -> lockdown.enter(
                        params.get("reason") instanceof String reason ? reason : null,
                        session.client().map(info -> info.kind().name().toLowerCase(java.util.Locale.ROOT))
                                .orElse("desconhecido")))
                .register("security.resume", (session, params) -> {
                    // Sair do só leitura é ação na tela: voz, host e automação não retomam (SPEC-015 CA-6).
                    if (!session.is(ClientKind.DESKTOP)) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED,
                                "só a janela do Zordon retoma de um lockdown");
                    }
                    return lockdown.resume();
                })
                .register("security.status", (session, params) -> status())
                .onSessionReady(session -> {
                    if (session.is(ClientKind.DESKTOP) && session.capabilities().contains(DesktopApprover.CAPABILITY)) {
                        approver.desktopConnected(session.id());
                    }
                })
                .onSessionClosed(session -> approver.desktopDisconnected(session.id()));
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lockdown", lockdown.status());
        out.put("audit", audit.get());
        out.put("pendingNotifications", notifications.pending().size());
        out.put("approver", approver.available() ? "desktop" : "none");
        return out;
    }
}
