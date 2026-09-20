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
package zordon.core.permission;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Principal;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;
import zordon.core.zwp.ClientRequests;
import zordon.security.PermissionEngine;

/**
 * Pergunta ao usuário pela janela do desktop (SPEC-015 CA-1): o
 * {@code Approver} do motor de permissão, sobre {@code ui.requestPermission}.
 *
 * <p>O texto e os alvos vêm do {@link ActionDescriptor}, montado pelo núcleo; o
 * desktop só mostra e devolve a escolha.
 */
@Spec("SPEC-015")
public final class DesktopApprover implements PermissionEngine.Approver {

    public static final String CAPABILITY = "ui.permission-prompt";
    public static final int MAX_TARGETS_SHOWN = 50;

    private static final Logger log = LoggerFactory.getLogger(DesktopApprover.class);

    private final ClientRequests clients;
    /** Desktops que mostram o diálogo, em ordem de chegada; o mais recente pergunta. */
    private final Deque<String> desktops = new ArrayDeque<>();

    public DesktopApprover(ClientRequests clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    public synchronized void desktopConnected(String sessionId) {
        desktops.remove(sessionId);
        desktops.addLast(sessionId);
    }

    public synchronized void desktopDisconnected(String sessionId) {
        desktops.remove(sessionId);
    }

    public synchronized boolean available() {
        return !desktops.isEmpty();
    }

    @Override
    public CompletableFuture<PermissionEngine.Approval> ask(ActionDescriptor action, Principal actor, RiskLevel risk,
            Duration ttl, boolean perAction) {
        String desktop;
        synchronized (this) {
            desktop = desktops.peekLast();
        }
        if (desktop == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("nenhum desktop conectado"));
        }
        String requestId = "perm-" + UUID.randomUUID();
        List<String> targets = action.touchedPaths().stream().limit(MAX_TARGETS_SHOWN).map(ZPath::toString).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId);
        params.put("tool", action.tool());
        params.put("summary", action.humanSummary());
        params.put("risk", risk.wire());
        params.put("origin", actor.origin().wire());
        params.put("targets", targets);
        params.put("targetCount", Math.max(action.targets(), targets.size()));
        params.put("perAction", perAction);
        params.put("ttlMs", ttl.toMillis());
        log.info("pedindo autorização {} ({}, {}) ao desktop {}", requestId, action.tool(), risk.wire(), desktop);
        return clients.request(desktop, "ui.requestPermission", params, ttl)
                .thenApply(answer -> {
                    PermissionEngine.Approval approval = switch (String.valueOf(answer.get("approval"))) {
                        case "once" -> PermissionEngine.Approval.ONCE;
                        case "session" -> perAction ? PermissionEngine.Approval.ONCE : PermissionEngine.Approval.SESSION;
                        default -> PermissionEngine.Approval.DENY;
                    };
                    log.info("autorização {}: {}", requestId, approval);
                    return approval;
                });
    }
}
