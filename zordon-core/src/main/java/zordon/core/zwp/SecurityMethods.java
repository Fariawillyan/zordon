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
import zordon.core.permission.OppressorService;

/**
 * Notificações, kill switch e o registro dos desktops que autorizam
 * (SPEC-015 §7).
 */
@Spec("SPEC-015")
public final class SecurityMethods {

    private final NotificationCenter notifications;
    private final LockdownService lockdown;
    private final OppressorService oppressor;
    private final DesktopApprover approver;
    private final Supplier<Map<String, Object>> audit;

    public SecurityMethods(NotificationCenter notifications, LockdownService lockdown, OppressorService oppressor,
            DesktopApprover approver, Supplier<Map<String, Object>> audit) {
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.lockdown = Objects.requireNonNull(lockdown, "lockdown");
        this.oppressor = Objects.requireNonNull(oppressor, "oppressor");
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
                .register("security.oppressor.enter", (session, params) -> {
                    if (!oppressor.enter(secret(params, "password"), kindOf(session))) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED,
                                oppressor.configured() ? "senha mestre incorreta" : "sem senha mestre cadastrada");
                    }
                    return oppressor.status();
                })
                .register("security.oppressor.exit", (session, params) -> oppressor.exit(kindOf(session)))
                .register("security.oppressor.password", (session, params) -> {
                    if (!oppressor.password(secret(params, "current"), secret(params, "next"))) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED,
                                "a senha mestre atual não confere");
                    }
                    return Map.of("configured", true);
                })
                .register("security.status", (session, params) -> status())
                .onSessionReady(session -> {
                    if (session.is(ClientKind.DESKTOP) && session.capabilities().contains(DesktopApprover.CAPABILITY)) {
                        approver.desktopConnected(session.id());
                    }
                })
                .onSessionClosed(session -> approver.desktopDisconnected(session.id()));
    }

    private static String kindOf(ZwpSession session) {
        return session.client().map(info -> info.kind().name().toLowerCase(java.util.Locale.ROOT))
                .orElse("desconhecido");
    }

    /**
     * Uma senha vinda dos parâmetros, como vetor para poder ser zerada.
     *
     * <p>O JSON já a trouxe como {@code String} imutável, então essa cópia é
     * que o {@link OppressorService} zera; a original só sai da memória quando
     * o coletor quiser. Trocar isso exigiria a senha em binário no protocolo, e
     * não vale o preço: quem lê a memória do processo já ganhou.
     */
    private static char[] secret(Map<String, Object> params, String field) {
        return params.get(field) instanceof String value && !value.isEmpty() ? value.toCharArray() : new char[0];
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lockdown", lockdown.status());
        out.put("oppressor", oppressor.status());
        out.put("audit", audit.get());
        out.put("pendingNotifications", notifications.pending().size());
        out.put("approver", approver.available() ? "desktop" : "none");
        return out;
    }
}
